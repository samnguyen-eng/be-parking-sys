package com.parking.worker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.parking.worker.entity.ParkingSpace;
import com.parking.worker.entity.ParkingSpaceStatus;
import com.parking.worker.entity.Reservation;
import com.parking.worker.entity.ReservationStatus;
import com.parking.worker.entity.Account;
import com.parking.worker.repository.AccountRepository;
import com.parking.worker.repository.ParkingSpaceRepository;
import com.parking.worker.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class ReservationWorkerService {

    private static final String PROCESSED_KEY_PREFIX = "processed:msg:";
    private static final String PROCESSING_KEY_PREFIX = "processing:msg:";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration PROCESSING_LOCK_TTL = Duration.ofMinutes(2);
    private static final String SPACES_CACHE_KEY = "parking:spaces:all";
    private static final String AVAILABLE_SLOTS_KEY = "parking:spaces:available";
    private static final long SPACES_CACHE_TTL_SECONDS = 30L;

    private final ParkingSpaceRepository parkingSpaceRepository;
    private final ReservationRepository reservationRepository;
    private final AccountRepository accountRepository;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    /**
     * Processes a decoded Pub/Sub message payload.
     *
     * @param messageId unique Pub/Sub message ID used for idempotency
     * @param payload   JSON string, e.g. {"reservationId":1,"spaceId":4}
     */
    public void process(String messageId, String payload) {
        log.info("Processing message id={} payload={}", messageId, payload);

        String idempotencyKey = resolveIdempotencyKey(payload, messageId);

        // 1. Idempotency check via Redis (already processed)
        String processedKey = PROCESSED_KEY_PREFIX + idempotencyKey;
        RBucket<String> processedBucket = redissonClient.getBucket(processedKey);
        if (processedBucket.isExists()) {
            log.info("Idempotency key={} already processed (idempotency hit). Skipping.", idempotencyKey);
            return;
        }

        // 2. Atomic claim for in-flight processing across instances
        String processingKey = PROCESSING_KEY_PREFIX + idempotencyKey;
        RBucket<String> processingBucket = redissonClient.getBucket(processingKey);
        boolean claimed = processingBucket.trySet(
                "processing",
                PROCESSING_LOCK_TTL.toSeconds(),
                TimeUnit.SECONDS
        );
        if (!claimed) {
            log.info("Idempotency key={} is being processed by another instance. Skipping.", idempotencyKey);
            return;
        }

        boolean completed = false;

        try {
            // 3. Parse payload
            Long reservationId;
            Long spaceId;
            String plateNumber;
            JsonNode node = objectMapper.readTree(payload);
            reservationId = node.path("reservationId").asLong();
            spaceId = node.path("spaceId").asLong();
            plateNumber = node.path("plateNumber").asText("");
            if (reservationId <= 0 || spaceId <= 0) {
                throw new IllegalArgumentException("reservationId/spaceId must be positive");
            }

            // 4. Load reservation; skip if not PENDING
            Optional<Reservation> optReservation = reservationRepository.findById(reservationId);
            if (optReservation.isEmpty()) {
                log.warn("Reservation id={} not found. Skipping message id={}.", reservationId, messageId);
                markProcessed(processedBucket);
                completed = true;
                return;
            }
            Reservation reservation = optReservation.get();
            if (reservation.getStatus() != ReservationStatus.PENDING) {
                log.info("Reservation id={} status={} (not PENDING). Skipping message id={}.",
                        reservationId, reservation.getStatus(), messageId);
                markProcessed(processedBucket);
                completed = true;
                return;
            }

            // 5. Lock the exact parking space reserved by parking-api
            Optional<ParkingSpace> optSpace = parkingSpaceRepository.findByIdForUpdate(spaceId);
            if (optSpace.isEmpty()) {
                compensateAndCancelReservation(reservation, "Space not found: " + spaceId);
                markProcessed(processedBucket);
                completed = true;
                invalidateSpacesCache();
                return;
            }

            ParkingSpace space = optSpace.get();

            // 6. Ensure space is still RESERVED by API step
            if (space.getStatus() != ParkingSpaceStatus.RESERVED) {
                compensateAndCancelReservation(reservation, "Space is not reserved: " + space.getId());
                markProcessed(processedBucket);
                completed = true;
                invalidateSpacesCache();
                return;
            }

            // 7. Confirm the reservation
            reservation.setSpaceId(space.getId());
            reservation.setStatus(ReservationStatus.CONFIRMED);
            reservationRepository.save(reservation);
            updateSpacesCacheAsReserved(space.getId(), plateNumber);
            log.info("Reservation id={} CONFIRMED with space id={}", reservationId, space.getId());

            // 8. Mark as processed in Redis with TTL
            markProcessed(processedBucket);
            completed = true;
        } catch (Exception e) {
            log.error("Failed to process message id={}: {}", messageId, e.getMessage(), e);
            throw new RuntimeException("Failed to process message: " + e.getMessage(), e);
        } finally {
            // Allow retries to claim and re-process on failures.
            processingBucket.delete();
        }
    }

    private void markProcessed(RBucket<String> bucket) {
        bucket.set("done", IDEMPOTENCY_TTL);
    }

    private void compensateAndCancelReservation(Reservation reservation, String reason) {
        log.warn("Compensating reservation id={}: {}", reservation.getId(), reason);
        releaseSpace(reservation.getSpaceId());
        refundAccount(reservation.getUserId(), reservation.getAmount());
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservationRepository.save(reservation);
        updateSpacesCacheAsAvailable(reservation.getSpaceId());
    }

    private void releaseSpace(Long spaceId) {
        if (spaceId == null) {
            return;
        }
        parkingSpaceRepository.findByIdForUpdate(spaceId).ifPresent(space -> {
            space.setStatus(ParkingSpaceStatus.AVAILABLE);
            parkingSpaceRepository.save(space);
            addSpaceBackToAvailableCache(spaceId);
        });
    }

    private void refundAccount(Long userId, BigDecimal amount) {
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

    private void addSpaceBackToAvailableCache(Long spaceId) {
        RScoredSortedSet<Long> availableSlots = redissonClient.getScoredSortedSet(AVAILABLE_SLOTS_KEY);
        availableSlots.add(spaceId.doubleValue(), spaceId);
        availableSlots.expire(60, TimeUnit.SECONDS);
    }

    private void updateSpacesCacheAsReserved(Long spaceId, String plateNumber) {
        updateSpacesCache(spaceId, ParkingSpaceStatus.RESERVED.name(), maskPlateNumber(plateNumber));
    }

    private void updateSpacesCacheAsAvailable(Long spaceId) {
        updateSpacesCache(spaceId, ParkingSpaceStatus.AVAILABLE.name(), null);
    }

    private void updateSpacesCache(Long spaceId, String status, String occupantPlate) {
        if (spaceId == null) {
            return;
        }
        RBucket<String> bucket = redissonClient.getBucket(SPACES_CACHE_KEY);
        String cachedPayload = bucket.get();
        if (cachedPayload == null || cachedPayload.isBlank()) {
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(cachedPayload);
            if (!(root instanceof ArrayNode arrayNode)) {
                return;
            }

            for (JsonNode node : arrayNode) {
                if (node.path("id").asLong() == spaceId && node instanceof ObjectNode objectNode) {
                    objectNode.put("status", status);
                    if (occupantPlate == null) {
                        objectNode.putNull("occupantPlate");
                    } else {
                        objectNode.put("occupantPlate", occupantPlate);
                    }
                    break;
                }
            }
            bucket.set(objectMapper.writeValueAsString(arrayNode), SPACES_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ex) {
            log.warn("Failed to update spaces cache for spaceId={}", spaceId, ex);
            invalidateSpacesCache();
        }
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

    private String resolveIdempotencyKey(String payload, String fallbackMessageId) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            long reservationId = node.path("reservationId").asLong(0L);
            if (reservationId > 0) {
                return "reservation:" + reservationId;
            }
        } catch (Exception ignored) {
            // fallback to Pub/Sub message id
        }
        return "message:" + fallbackMessageId;
    }

    public void compensateOnMaxRetry(String payload, String reason) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            long reservationId = node.path("reservationId").asLong(0L);
            if (reservationId <= 0) {
                log.error("Skip max-retry compensation due to invalid reservationId in payload. reason={}", reason);
                return;
            }

            Optional<Reservation> optReservation = reservationRepository.findById(reservationId);
            if (optReservation.isEmpty()) {
                log.warn("Skip max-retry compensation because reservation id={} was not found.", reservationId);
                return;
            }

            Reservation reservation = optReservation.get();
            if (reservation.getStatus() != ReservationStatus.PENDING) {
                log.info("Skip max-retry compensation for reservation id={} because status={} (not PENDING).",
                        reservationId, reservation.getStatus());
                return;
            }

            compensateAndCancelReservation(reservation, "Max retry reached: " + reason);
            invalidateSpacesCache();
            log.warn("Max-retry compensation completed for reservation id={}", reservationId);
        } catch (Exception ex) {
            throw new RuntimeException("Failed max-retry compensation: " + ex.getMessage(), ex);
        }
    }
}
