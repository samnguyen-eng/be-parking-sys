package com.parking.api.service;

import com.parking.api.entity.ParkingSpace;
import com.parking.api.entity.ParkingSpaceStatus;
import com.parking.api.entity.Reservation;
import com.parking.api.entity.ReservationStatus;
import com.parking.api.repository.AccountRepository;
import com.parking.api.repository.ParkingSpaceRepository;
import com.parking.api.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationExpiryService {

    private static final String ACCOUNT_BALANCE_KEY_PATTERN = "account:balance:%d";
    private static final long ACCOUNT_BALANCE_TTL_HOURS = 24L;

    private final ReservationRepository reservationRepository;
    private final ParkingSpaceRepository parkingSpaceRepository;
    private final AccountRepository accountRepository;
    private final ReservationCacheGuard reservationCacheGuard;
    private final RedissonClient redissonClient;

    /**
     * Lazy expiry when user loads reservation history — marks holds older than 24h as EXPIRED.
     */
    @Transactional
    public void expireReservationsForUser(Long userId) {
        List<Reservation> expiredReservations =
                reservationRepository.findExpiredByUserIdForUpdate(userId, LocalDateTime.now());
        if (expiredReservations.isEmpty()) {
            return;
        }

        boolean cacheInvalidated = false;
        for (Reservation reservation : expiredReservations) {
            expireReservation(reservation);
            cacheInvalidated = true;
        }

        if (cacheInvalidated) {
            reservationCacheGuard.invalidateSpacesListCache();
        }
    }

    private void expireReservation(Reservation reservation) {
        Long userId = reservation.getUser() != null ? reservation.getUser().getId() : null;
        Long spaceId = reservation.getSpace() != null ? reservation.getSpace().getId() : null;

        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            refundBalance(userId, reservation.getAmount());
        }

        releaseSpaceIfEligible(spaceId, reservation.getId());
        releaseRedisClaims(userId, spaceId, reservation.getReservationDate());

        reservation.setStatus(ReservationStatus.EXPIRED);
        reservationRepository.save(reservation);
        log.info("Expired reservation id={} for userId={} on load", reservation.getId(), userId);
    }

    private void releaseSpaceIfEligible(Long spaceId, Long reservationId) {
        if (spaceId == null) {
            return;
        }
        if (reservationRepository.existsConfirmedForSpaceExcluding(spaceId, reservationId)) {
            return;
        }
        parkingSpaceRepository.findByIdForUpdate(spaceId).ifPresent(space -> {
            if (space.getStatus() == ParkingSpaceStatus.RESERVED) {
                space.setStatus(ParkingSpaceStatus.AVAILABLE);
                parkingSpaceRepository.save(space);
            }
        });
    }

    private void releaseRedisClaims(Long userId, Long spaceId, java.time.LocalDate reservationDate) {
        if (userId == null || spaceId == null || reservationDate == null) {
            return;
        }
        reservationCacheGuard.releaseReserveClaims(userId, spaceId, reservationDate);
    }

    private void refundBalance(Long userId, BigDecimal amount) {
        if (userId == null || amount == null) {
            return;
        }
        accountRepository.findByUserIdForUpdate(userId).ifPresent(account -> {
            account.setBalance(account.getBalance().add(amount));
            accountRepository.save(account);
            updateBalanceCache(userId, account.getBalance());
        });
    }

    private void updateBalanceCache(Long userId, BigDecimal balance) {
        redissonClient.getBucket(String.format(ACCOUNT_BALANCE_KEY_PATTERN, userId))
                .set(balance.toPlainString(), ACCOUNT_BALANCE_TTL_HOURS, TimeUnit.HOURS);
    }
}
