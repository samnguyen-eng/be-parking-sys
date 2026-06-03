package com.parking.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parking.api.dto.request.ReserveRequest;
import com.parking.api.entity.ParkingSpace;
import com.parking.api.entity.Reservation;
import com.parking.api.entity.ReservationStatus;
import com.parking.api.entity.User;
import com.parking.api.exception.BusinessException;
import com.parking.api.repository.ParkingSpaceRepository;
import com.parking.api.repository.ReservationRepository;
import com.parking.api.repository.UserRepository;
import com.parking.api.service.dto.ReservationCreatedResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationCommandService {

    private static final BigDecimal PRICE_PER_DAY = new BigDecimal("10.00");
    private static final long RESERVATION_HOLD_HOURS = 24L;

    private final ReservationRepository reservationRepository;
    private final ParkingSpaceRepository parkingSpaceRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final ReservationPubSubPublisher reservationPubSubPublisher;
    private final ReservationCacheGuard reservationCacheGuard;

    @Transactional(timeout = 5, rollbackFor = Exception.class)
    public ReservationCreatedResult createReservation(Long userId, ReserveRequest request, LocalDate reservationDate, String plateNumber) {
        try {
            return doCreateReservation(userId, request, reservationDate, plateNumber);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new BusinessException("You already have a reservation for " + reservationDate);
        }
    }

    private ReservationCreatedResult doCreateReservation(
            Long userId, ReserveRequest request, LocalDate reservationDate, String plateNumber) {

        // Use proxy references — no DB query needed
        User user = userRepository.getReferenceById(userId);
        ParkingSpace selectedSpace = parkingSpaceRepository.getReferenceById(request.getSpaceId());

        // Balance check done in Redis (tryReserveClaims). Worker deducts DB + updates cache on CONFIRM.
        // Only a lightweight INSERT — no SELECT FOR UPDATE on account.
        Reservation reservation = Reservation.builder()
                .user(user)
                .space(selectedSpace)
                .reservationDate(reservationDate)
                .status(ReservationStatus.PENDING)
                .amount(PRICE_PER_DAY)
                .expiresAt(LocalDateTime.now().plusHours(RESERVATION_HOLD_HOURS))
                .build();
        Reservation savedReservation = reservationRepository.save(reservation);

        log.info("Reservation queued: reservationId={}, spaceId={}, userId={} (PENDING)",
                savedReservation.getId(), request.getSpaceId(), userId);

        String payload = buildPayload(
                savedReservation.getId(), userId, request.getSpaceId(), plateNumber, reservationDate);

        schedulePublishAfterCommit(payload, request.getSpaceId(), reservationDate);

        return new ReservationCreatedResult(
                savedReservation.getId(),
                savedReservation.getStatus().name(),
                request.getSpaceId(),
                null,
                reservationDate,
                PRICE_PER_DAY,
                null, // balance returned async — worker handles DB deduction
                payload
        );
    }

    private void schedulePublishAfterCommit(String payload, Long spaceId, LocalDate reservationDate) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                reservationPubSubPublisher.publishReservationCreatedAfterCommit(
                        payload, spaceId, reservationDate);
            }
        });
    }

    public String resolvePlateNumber(Long userId, ReserveRequest request) {
        if (request.getPlateNumber() != null && !request.getPlateNumber().isBlank()) {
            return request.getPlateNumber().toUpperCase();
        }
        String cached = reservationCacheGuard.getUserPlateFromCache(userId);
        if (cached != null) {
            return cached;
        }
        return userRepository.findById(userId)
                .map(User::getPlateNumber)
                .filter(plate -> plate != null && !plate.isBlank())
                .map(plate -> {
                    String normalized = plate.toUpperCase();
                    reservationCacheGuard.cacheUserPlate(userId, normalized);
                    return normalized;
                })
                .orElseThrow(() -> new BusinessException("Plate number is required for reservation"));
    }

    private String buildPayload(
            Long reservationId, Long userId, Long spaceId, String plateNumber, LocalDate reservationDate) {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("reservationId", reservationId);
            map.put("userId", userId);
            map.put("spaceId", spaceId);
            map.put("plateNumber", plateNumber);
            map.put("reservationDate", reservationDate.toString());
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize Pub/Sub payload", e);
            throw new BusinessException("Reservation failed due to an unexpected error.");
        }
    }
}
