package com.parking.api.controller;

import com.parking.api.dto.request.ReserveRequest;
import com.parking.api.dto.response.ApiResponse;
import com.parking.api.dto.response.ParkingSpaceResponse;
import com.parking.api.dto.response.ReserveResponse;
import com.parking.api.dto.response.UserReservationResponse;
import com.parking.api.exception.BusinessException;
import com.parking.api.repository.UserRepository;
import com.parking.api.service.ParkingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/parking")
@RequiredArgsConstructor
public class ParkingController {

    private final ParkingService parkingService;
    private final UserRepository userRepository;

    /**
     * GET /api/parking/spaces — public endpoint, no auth required.
     * Returns all 80 parking spaces with current status.
     */
    @GetMapping("/spaces")
    public ResponseEntity<ApiResponse<List<ParkingSpaceResponse>>> getSpaces() {
        List<ParkingSpaceResponse> spaces = parkingService.getSpaces();
        return ResponseEntity.ok(ApiResponse.success(spaces));
    }

    @GetMapping("/reservations/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<UserReservationResponse>>> getMyReservations(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = resolveUserId(userDetails.getUsername());
        List<UserReservationResponse> reservations = parkingService.getMyReservations(userId);
        return ResponseEntity.ok(ApiResponse.success(reservations));
    }

    /**
     * POST /api/parking/reserve — authenticated endpoint.
     * Reserves a parking space for the authenticated user.
     */
    @PostMapping("/reserve")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<ReserveResponse>> reserve(
            @Valid @RequestBody ReserveRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {

        Long userId = resolveUserId(userDetails.getUsername());
        log.info("Reserve request: userId={}, date={}", userId, request.getReservationDate());
        ReserveResponse response = parkingService.reserve(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Reservation created successfully", response));
    }

    private Long resolveUserId(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException("User not found: " + username, HttpStatus.NOT_FOUND))
                .getId();
    }
}
