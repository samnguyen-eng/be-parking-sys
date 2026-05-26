package com.parking.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parking.api.dto.request.ReserveRequest;
import com.parking.api.dto.response.ParkingSpaceResponse;
import com.parking.api.dto.response.ReserveResponse;
import com.parking.api.entity.*;
import com.parking.api.exception.BusinessException;
import com.parking.api.exception.ResourceNotFoundException;
import com.parking.api.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParkingService {

    private static final String RESERVE_LOCK_KEY = "parking:reserve:lock";
    private static final String SPACES_CACHE_KEY = "parking:spaces:all";
    private static final long SPACES_CACHE_TTL_SECONDS = 30L;
    private static final BigDecimal PRICE_PER_DAY = new BigDecimal("10.00");

    private final RedissonClient redissonClient;
    private final ReservationRepository reservationRepository;
    private final ParkingSpaceRepository parkingSpaceRepository;
    private final AccountRepository accountRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.local-mode:true}")
    private boolean localMode;

    @Value("${app.parking.lock-ttl-seconds:10}")
    private long lockTtlSeconds;

    @Value("${app.parking.reservation-open-hour:20}")
    private int reservationOpenHour;

    // -------------------------------------------------------------------------
    // Reserve a parking space
    // -------------------------------------------------------------------------
    @Transactional
    public ReserveResponse reserve(Long userId, ReserveRequest request) {

        // 1. Check reservation open hour (skip in local mode)
        if (!localMode) {
            int currentHour = LocalTime.now().getHour();
            if (currentHour < reservationOpenHour) {
                throw new BusinessException(
                        "Reservations open at " + reservationOpenHour + ":00. Please try again later.",
                        HttpStatus.FORBIDDEN);
            }
        } else {
            log.debug("local-mode=true: skipping reservation open-hour check");
        }

        // Resolve reservation date (default = today)
        LocalDate reservationDate = (request.getReservationDate() != null)
                ? request.getReservationDate()
                : LocalDate.now();

        // 2. Check duplicate reservation for the same date
        if (reservationRepository.existsByUserIdAndReservationDate(userId, reservationDate)) {
            throw new BusinessException("You already have a reservation for " + reservationDate);
        }

        // Load user
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        // Resolve plate number (request override > user profile)
        String plateNumber = (request.getPlateNumber() != null && !request.getPlateNumber().isBlank())
                ? request.getPlateNumber().toUpperCase()
                : user.getPlateNumber();

        // 3. Deduct balance with pessimistic lock
        Account account = accountRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found for userId: " + userId));

        if (account.getBalance().compareTo(PRICE_PER_DAY) < 0) {
            throw new BusinessException(
                    "Insufficient balance. Required: $" + PRICE_PER_DAY
                    + ", Available: $" + account.getBalance());
        }
        account.setBalance(account.getBalance().subtract(PRICE_PER_DAY));
        accountRepository.save(account);
        log.debug("Deducted ${}. Remaining balance: {} for userId={}", PRICE_PER_DAY, account.getBalance(), userId);

        // 4. Acquire Redisson distributed lock
        RLock lock = redissonClient.getLock(RESERVE_LOCK_KEY);
        boolean locked = false;
        Reservation savedReservation;

        try {
            locked = lock.tryLock(lockTtlSeconds, lockTtlSeconds, TimeUnit.SECONDS);
            if (!locked) {
                rollbackBalance(account, PRICE_PER_DAY);
                throw new BusinessException("System is busy, please try again shortly.",
                        HttpStatus.SERVICE_UNAVAILABLE);
            }

            // 5. Find first available space (PESSIMISTIC_WRITE inside distributed lock)
            ParkingSpace space = parkingSpaceRepository.findFirstAvailable()
                    .orElseThrow(() -> {
                        rollbackBalance(account, PRICE_PER_DAY);
                        return new BusinessException("No parking spaces available at this time.", HttpStatus.CONFLICT);
                    });

            // Mark space RESERVED
            space.setStatus(ParkingSpaceStatus.RESERVED);
            parkingSpaceRepository.save(space);

            // 7. Create Reservation (status = PENDING)
            Reservation reservation = Reservation.builder()
                    .user(user)
                    .space(space)
                    .reservationDate(reservationDate)
                    .status(ReservationStatus.PENDING)
                    .amount(PRICE_PER_DAY)
                    .build();
            savedReservation = reservationRepository.save(reservation);

            // 8. Create OutboxEvent for downstream processing
            String payload = buildPayload(savedReservation.getId(), userId, space.getId(), plateNumber);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateType("RESERVATION")
                    .aggregateId(savedReservation.getId())
                    .eventType("reservation.created")
                    .payload(payload)
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .build();
            outboxEventRepository.save(outboxEvent);
            invalidateSpacesCache();

            log.info("Reservation created: reservationId={}, space={}, userId={}",
                    savedReservation.getId(), space.getSpaceNumber(), userId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rollbackBalance(account, PRICE_PER_DAY);
            throw new BusinessException("Reservation was interrupted. Please try again.");
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            rollbackBalance(account, PRICE_PER_DAY);
            log.error("Unexpected error during reservation for userId={}", userId, ex);
            throw new BusinessException("Reservation failed due to an unexpected error.");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        return ReserveResponse.builder()
                .reservationId(savedReservation.getId())
                .status(savedReservation.getStatus().name())
                .message("Reservation successful! Space: " + savedReservation.getSpace().getSpaceNumber())
                .reservationDate(reservationDate)
                .amount(PRICE_PER_DAY)
                .remainingBalance(account.getBalance())
                .build();
    }

    // -------------------------------------------------------------------------
    // List all 80 spaces with occupant plate (last 3 digits)
    // -------------------------------------------------------------------------
    @Transactional(readOnly = true)
    public List<ParkingSpaceResponse> getSpaces() {
        RBucket<String> cacheBucket = redissonClient.getBucket(SPACES_CACHE_KEY);
        String cachedPayload = cacheBucket.get();
        if (cachedPayload != null && !cachedPayload.isBlank()) {
            try {
                return objectMapper.readValue(cachedPayload, new TypeReference<List<ParkingSpaceResponse>>() {});
            } catch (JsonProcessingException ex) {
                log.warn("Failed to deserialize parking spaces cache, falling back to DB", ex);
            }
        }

        List<ParkingSpaceResponse> response = loadSpacesFromDb();
        try {
            String payload = objectMapper.writeValueAsString(response);
            cacheBucket.set(payload, SPACES_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize parking spaces for cache", ex);
        }
        return response;
    }

    private List<ParkingSpaceResponse> loadSpacesFromDb() {
        List<ParkingSpace> spaces = parkingSpaceRepository.findAllByOrderBySpaceNumberAsc();

        // Build spaceId -> last-3-digits-of-plate map from today's active reservations
        Map<Long, String> spaceToPlate = reservationRepository
                .findActiveByReservationDate(LocalDate.now())
                .stream()
                .filter(r -> r.getSpace() != null && r.getUser() != null)
                .collect(Collectors.toMap(
                        r -> r.getSpace().getId(),
                        r -> {
                            String plate = r.getUser().getPlateNumber();
                            if (plate == null || plate.isBlank()) return "???";
                            return plate.length() >= 3 ? plate.substring(plate.length() - 3) : plate;
                        },
                        (existing, replacement) -> existing  // keep first on duplicate
                ));

        return spaces.stream()
                .map(space -> ParkingSpaceResponse.builder()
                        .id(space.getId())
                        .spaceNumber(space.getSpaceNumber())
                        .status(space.getStatus().name())
                        .occupantPlate(space.getStatus() == ParkingSpaceStatus.RESERVED
                                ? spaceToPlate.get(space.getId())
                                : null)
                        .build())
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private void rollbackBalance(Account account, BigDecimal amount) {
        try {
            Account fresh = accountRepository.findByUserIdForUpdate(account.getUser().getId())
                    .orElse(account);
            fresh.setBalance(fresh.getBalance().add(amount));
            accountRepository.save(fresh);
            log.warn("Rolled back ${}  for userId={}", amount, account.getUser().getId());
        } catch (Exception e) {
            log.error("CRITICAL: failed to rollback ${}  for userId={}: {}",
                    amount, account.getUser().getId(), e.getMessage());
        }
    }

    private String buildPayload(Long reservationId, Long userId, Long spaceId, String plateNumber) {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("reservationId", reservationId);
            map.put("userId", userId);
            map.put("spaceId", spaceId);
            map.put("plateNumber", plateNumber);
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox payload", e);
            return "{}";
        }
    }

    private void invalidateSpacesCache() {
        redissonClient.getBucket(SPACES_CACHE_KEY).delete();
    }
}
