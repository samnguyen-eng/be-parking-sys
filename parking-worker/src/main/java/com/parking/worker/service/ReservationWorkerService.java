package com.parking.worker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class ReservationWorkerService {

    private static final String IDEMPOTENCY_KEY_PREFIX = "processed:msg:";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final String SPACES_CACHE_KEY = "parking:spaces:all";

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

        // 1. Idempotency check via Redis
        String redisKey = IDEMPOTENCY_KEY_PREFIX + messageId;
        RBucket<String> bucket = redissonClient.getBucket(redisKey);
        if (bucket.isExists()) {
            log.info("Message id={} already processed (idempotency hit). Skipping.", messageId);
            return;
        }

        // 2. Parse payload
        Long reservationId;
        Long spaceId;
        try {
            JsonNode node = objectMapper.readTree(payload);
            reservationId = node.path("reservationId").asLong();
            spaceId = node.path("spaceId").asLong();
            if (reservationId <= 0 || spaceId <= 0) {
                throw new IllegalArgumentException("reservationId/spaceId must be positive");
            }
        } catch (Exception e) {
            log.error("Failed to parse payload for message id={}: {}", messageId, e.getMessage());
            throw new RuntimeException("Invalid message payload: " + e.getMessage(), e);
        }

        // 3. Load reservation; skip if not PENDING
        Optional<Reservation> optReservation = reservationRepository.findById(reservationId);
        if (optReservation.isEmpty()) {
            log.warn("Reservation id={} not found. Skipping message id={}.", reservationId, messageId);
            markProcessed(bucket);
            return;
        }
        Reservation reservation = optReservation.get();
        if (reservation.getStatus() != ReservationStatus.PENDING) {
            log.info("Reservation id={} status={} (not PENDING). Skipping message id={}.",
                    reservationId, reservation.getStatus(), messageId);
            markProcessed(bucket);
            return;
        }

        // 4. Lock the exact parking space reserved by parking-api
        Optional<ParkingSpace> optSpace = parkingSpaceRepository.findByIdForUpdate(spaceId);
        if (optSpace.isEmpty()) {
            compensateAndCancelReservation(reservation, "Space not found: " + spaceId);
            markProcessed(bucket);
            invalidateSpacesCache();
            return;
        }

        ParkingSpace space = optSpace.get();

        // 5. Ensure space is still RESERVED by API step
        if (space.getStatus() != ParkingSpaceStatus.RESERVED) {
            compensateAndCancelReservation(reservation, "Space is not reserved: " + space.getId());
            markProcessed(bucket);
            invalidateSpacesCache();
            return;
        }

        // 6. Confirm the reservation
        reservation.setSpaceId(space.getId());
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservationRepository.save(reservation);
        invalidateSpacesCache();
        log.info("Reservation id={} CONFIRMED with space id={}", reservationId, space.getId());

        // 7. Mark as processed in Redis with TTL
        markProcessed(bucket);
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
}
