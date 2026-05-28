package com.parking.outbox.service;

import com.parking.outbox.entity.Account;
import com.parking.outbox.entity.ParkingSpace;
import com.parking.outbox.entity.ParkingSpaceStatus;
import com.parking.outbox.entity.Reservation;
import com.parking.outbox.entity.ReservationStatus;
import com.parking.outbox.repository.AccountRepository;
import com.parking.outbox.repository.ParkingSpaceRepository;
import com.parking.outbox.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationExpiryService {

    private static final String SPACES_CACHE_KEY = "parking:spaces:all";

    private final ReservationRepository reservationRepository;
    private final ParkingSpaceRepository parkingSpaceRepository;
    private final AccountRepository accountRepository;
    private final RedissonClient redissonClient;

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void releaseExpiredReservations() {
        List<Reservation> expiredReservations = reservationRepository.findExpiredForUpdate(LocalDateTime.now());
        if (expiredReservations.isEmpty()) {
            return;
        }

        for (Reservation reservation : expiredReservations) {
            releaseSpace(reservation.getSpaceId());
            refundBalance(reservation.getUserId(), reservation.getAmount());
            reservation.setStatus(ReservationStatus.EXPIRED);
            reservationRepository.save(reservation);
            log.info("Expired reservation id={} released for userId={}", reservation.getId(), reservation.getUserId());
        }

        invalidateSpacesCache();
    }

    private void releaseSpace(Long spaceId) {
        if (spaceId == null) {
            return;
        }
        parkingSpaceRepository.findByIdForUpdate(spaceId).ifPresent(space -> {
            space.setStatus(ParkingSpaceStatus.AVAILABLE);
            parkingSpaceRepository.save(space);
        });
    }

    private void refundBalance(Long userId, java.math.BigDecimal amount) {
        if (userId == null || amount == null) {
            return;
        }
        accountRepository.findByUserIdForUpdate(userId).ifPresent(account -> {
            account.setBalance(account.getBalance().add(amount));
            accountRepository.save(account);
        });
    }

    private void invalidateSpacesCache() {
        redissonClient.getBucket(SPACES_CACHE_KEY).delete();
    }
}
