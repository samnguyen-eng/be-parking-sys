package com.parking.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parking.api.dto.request.ReserveRequest;
import com.parking.api.dto.response.ParkingSpaceResponse;
import com.parking.api.dto.response.ReserveResponse;
import com.parking.api.dto.response.UserReservationResponse;
import com.parking.api.entity.ParkingSpace;
import com.parking.api.entity.ParkingSpaceStatus;
import com.parking.api.entity.Reservation;
import com.parking.api.repository.ReservationRepository;
import com.parking.api.service.dto.ReservationCreatedResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParkingService {

    private static final java.math.BigDecimal RESERVE_PRICE = new java.math.BigDecimal("10.00");

    private static final String SPACES_CACHE_KEY = ReservationCacheGuard.SPACES_LIST_CACHE_KEY;
    private static final long SPACES_CACHE_TTL_SECONDS = 30L;

    private final RedissonClient redissonClient;
    private final ReservationRepository reservationRepository;
    private final com.parking.api.repository.ParkingSpaceRepository parkingSpaceRepository;
    private final ReservationCacheGuard reservationCacheGuard;
    private final ReservationCommandService reservationCommandService;
    private final ObjectMapper objectMapper;

    public ReserveResponse reserve(Long userId, ReserveRequest request) {
        LocalDate reservationDate = (request.getReservationDate() != null)
                ? request.getReservationDate()
                : LocalDate.now();
        Long spaceId = request.getSpaceId();

        reservationCacheGuard.tryReserveClaims(userId, spaceId, reservationDate, RESERVE_PRICE);

        // Resolve plate BEFORE Redis claims and DB transaction — avoids DB call inside transaction
        String plateNumber = reservationCommandService.resolvePlateNumber(userId, request);

        ReservationCreatedResult result;
        try {
            result = reservationCommandService.createReservation(userId, request, reservationDate, plateNumber);
        } catch (RuntimeException ex) {
            reservationCacheGuard.releaseReserveClaims(userId, spaceId, reservationDate);
            throw ex;
        }

        return ReserveResponse.builder()
                .reservationId(result.reservationId())
                .status(result.status())
                .message("Reservation request accepted. Status: PENDING — waiting in queue for slot confirmation.")
                .reservationDate(result.reservationDate())
                .amount(result.amount())
                .remainingBalance(result.remainingBalance())
                .build();
    }

    @Transactional(readOnly = true)
    public List<ParkingSpaceResponse> getSpaces() {
        List<ParkingSpaceResponse> cached = readSpacesListFromCache();
        if (cached != null) {
            return cached;
        }

        List<ParkingSpaceResponse> loaded = reservationCacheGuard.computeWithSingleFlightLock(
                ReservationCacheGuard.LOCK_SPACES_LIST,
                () -> loadAndCacheSpacesList());

        if (loaded != null) {
            return loaded;
        }

        cached = readSpacesListFromCache();
        if (cached != null) {
            return cached;
        }

        log.warn("Spaces list lock contention — loading from DB without holding lock");
        return loadAndCacheSpacesList();
    }

    private List<ParkingSpaceResponse> readSpacesListFromCache() {
        RBucket<String> cacheBucket = redissonClient.getBucket(SPACES_CACHE_KEY);
        String cachedPayload = cacheBucket.get();
        if (cachedPayload == null || cachedPayload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(cachedPayload, new TypeReference<List<ParkingSpaceResponse>>() {});
        } catch (JsonProcessingException ex) {
            log.warn("Failed to deserialize parking spaces cache", ex);
            return null;
        }
    }

    private List<ParkingSpaceResponse> loadAndCacheSpacesList() {
        List<ParkingSpaceResponse> cached = readSpacesListFromCache();
        if (cached != null) {
            return cached;
        }

        List<ParkingSpaceResponse> response = loadSpacesFromDb();
        RScoredSortedSet<Long> availableSlots =
                redissonClient.getScoredSortedSet(ReservationCacheGuard.AVAILABLE_SLOTS_KEY);
        reservationCacheGuard.warmAvailableSlotsIfMissing(availableSlots);

        try {
            String payload = objectMapper.writeValueAsString(response);
            redissonClient.getBucket(SPACES_CACHE_KEY)
                    .set(payload, SPACES_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
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
