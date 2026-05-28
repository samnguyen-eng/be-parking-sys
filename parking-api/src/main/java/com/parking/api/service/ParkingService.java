package com.parking.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parking.api.dto.request.ReserveRequest;
import com.parking.api.dto.response.ParkingSpaceResponse;
import com.parking.api.dto.response.ReserveResponse;
import com.parking.api.dto.response.UserReservationResponse;
import com.parking.api.entity.*;
import com.parking.api.exception.BusinessException;
import com.parking.api.exception.ResourceNotFoundException;
import com.parking.api.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.api.RScoredSortedSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParkingService {

    private static final String RESERVE_LOCK_KEY_PATTERN = "parking:reserve:space:%d:%s";
    private static final String SPACES_CACHE_KEY = "parking:spaces:all";
    private static final String AVAILABLE_SLOTS_KEY = "parking:spaces:available";
    private static final long SPACES_CACHE_TTL_SECONDS = 30L;
    private static final BigDecimal PRICE_PER_DAY = new BigDecimal("10.00");
    private static final long RESERVATION_HOLD_HOURS = 24L;

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

        // 2. Acquire Redisson distributed lock first
        String lockKey = String.format(RESERVE_LOCK_KEY_PATTERN, request.getSpaceId(), reservationDate);
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        Reservation savedReservation;
        Account account;
        ParkingSpace selectedSpace;

        try {
            locked = lock.tryLock(lockTtlSeconds, lockTtlSeconds, TimeUnit.SECONDS);
            if (!locked) {
                throw new BusinessException("System is busy, please try again shortly.",
                        HttpStatus.SERVICE_UNAVAILABLE);
            }

            // 3. Check duplicate again while holding distributed lock
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

            // 4. Deduct balance with pessimistic lock
            account = accountRepository.findByUserIdForUpdate(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Account not found for userId: " + userId));

            if (account.getBalance().compareTo(PRICE_PER_DAY) < 0) {
                throw new BusinessException(
                        "Insufficient balance. Required: $" + PRICE_PER_DAY
                                + ", Available: $" + account.getBalance());
            }
            account.setBalance(account.getBalance().subtract(PRICE_PER_DAY));
            accountRepository.save(account);
            log.debug("Deducted ${}. Remaining balance: {} for userId={}", PRICE_PER_DAY, account.getBalance(), userId);

            // 5. Lock exactly the requested slot and reserve it
            selectedSpace = parkingSpaceRepository.findByIdForUpdate(request.getSpaceId())
                    .orElseThrow(() -> new ResourceNotFoundException("Parking space not found: " + request.getSpaceId()));
            if (selectedSpace.getStatus() != ParkingSpaceStatus.AVAILABLE) {
                throw new BusinessException("Selected space is no longer available.", HttpStatus.CONFLICT);
            }
            selectedSpace.setStatus(ParkingSpaceStatus.RESERVED);
            parkingSpaceRepository.save(selectedSpace);

            // Best-effort cache update only; DB lock above is the source of truth.
            RScoredSortedSet<Long> availableSlots = redissonClient.getScoredSortedSet(AVAILABLE_SLOTS_KEY);
            availableSlots.remove(selectedSpace.getId());

            // 7. Create Reservation (status = PENDING)
            Reservation reservation = Reservation.builder()
                    .user(user)
                    .space(selectedSpace)
                    .reservationDate(reservationDate)
                    .status(ReservationStatus.PENDING)
                    .amount(PRICE_PER_DAY)
                    .expiresAt(LocalDateTime.now().plusHours(RESERVATION_HOLD_HOURS))
                    .build();
            savedReservation = reservationRepository.save(reservation);

            // 8. Create OutboxEvent for downstream processing
            String payload = buildPayload(savedReservation.getId(), userId, selectedSpace.getId(), plateNumber);
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
                    savedReservation.getId(), selectedSpace.getSpaceNumber(), userId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Reservation was interrupted. Please try again.");
        } catch (DataIntegrityViolationException ex) {
            String message = ex.getMostSpecificCause() != null
                    ? ex.getMostSpecificCause().getMessage()
                    : ex.getMessage();
            if (message != null && message.contains("uq_space_date_active")) {
                throw new BusinessException("Selected space is already reserved for " + reservationDate, HttpStatus.CONFLICT);
            }
            throw new BusinessException("You already have a reservation for " + reservationDate);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
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

    @Transactional(readOnly = true)
    public List<UserReservationResponse> getMyReservations(Long userId) {
        return reservationRepository.findByUserIdWithSpace(userId)
                .stream()
                .map(reservation -> UserReservationResponse.builder()
                        .id(reservation.getId())
                        .spaceId(reservation.getSpace() != null ? reservation.getSpace().getId() : null)
                        .spaceNumber(reservation.getSpace() != null ? reservation.getSpace().getSpaceNumber() : null)
                        .status(reservation.getStatus().name())
                        .reservationDate(reservation.getReservationDate())
                        .amount(reservation.getAmount())
                        .expiresAt(reservation.getExpiresAt())
                        .build())
                .toList();
    }

    private List<ParkingSpaceResponse> loadSpacesFromDb() {
        List<ParkingSpace> spaces = parkingSpaceRepository.findAllByOrderBySpaceNumberAsc();

        List<Long> reservedSpaceIds = spaces.stream()
                .filter(space -> space.getStatus() == ParkingSpaceStatus.RESERVED)
                .map(ParkingSpace::getId)
                .toList();

        Map<Long, String> spaceToPlate = reservedSpaceIds.isEmpty()
                ? Map.of()
                : reservationRepository.findLatestActiveBySpaceIds(reservedSpaceIds)
                        .stream()
                        .filter(r -> r.getSpace() != null && r.getUser() != null)
                        .collect(Collectors.toMap(
                                r -> r.getSpace().getId(),
                                r -> maskPlateNumber(r.getUser().getPlateNumber()),
                                (existing, replacement) -> existing
                        ));

        return spaces.stream()
                .map(space -> ParkingSpaceResponse.builder()
                        .id(space.getId())
                        .spaceNumber(space.getSpaceNumber())
                        .status(space.getStatus().name())
                        .occupantPlate(space.getStatus() == ParkingSpaceStatus.RESERVED
                                ? spaceToPlate.getOrDefault(space.getId(), "*****")
                                : null)
                        .build())
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
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

    private void hydrateAvailableSlotsCache(RScoredSortedSet<Long> availableSlots) {
        List<Long> availableIds = parkingSpaceRepository.findAvailableSpaceIds();
        if (availableIds.isEmpty()) {
            return;
        }
        Set<Long> uniqueOrderedIds = new LinkedHashSet<>(availableIds);
        for (Long id : uniqueOrderedIds) {
            availableSlots.add(id.doubleValue(), id);
        }
        availableSlots.expire(60, TimeUnit.SECONDS);
    }

    private String maskPlateNumber(String plateNumber) {
        if (plateNumber == null || plateNumber.isBlank()) {
            return "*****";
        }
        String normalized = plateNumber.trim().toUpperCase();
        if (normalized.length() <= 3) {
            return "*****" + normalized;
        }
        return "*****" + normalized.substring(normalized.length() - 3);
    }
}
