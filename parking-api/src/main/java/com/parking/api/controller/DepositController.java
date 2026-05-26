package com.parking.api.controller;

import com.parking.api.dto.request.DepositRequest;
import com.parking.api.dto.response.ApiResponse;
import com.parking.api.dto.response.BalanceResponse;
import com.parking.api.dto.response.DepositResponse;
import com.parking.api.exception.BusinessException;
import com.parking.api.repository.UserRepository;
import com.parking.api.service.DepositService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DepositController {

    private final DepositService depositService;
    private final UserRepository userRepository;

    @PostMapping("/deposit")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<DepositResponse>> deposit(
            @Valid @RequestBody DepositRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {

        Long userId = resolveUserId(userDetails.getUsername());
        log.info("Deposit request: userId={}, amount={}", userId, request.getAmount());
        DepositResponse response = depositService.deposit(userId, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/balance")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<BalanceResponse>> getBalance(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = resolveUserId(userDetails.getUsername());
        BalanceResponse response = depositService.getBalance(userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    private Long resolveUserId(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException("User not found: " + username, HttpStatus.NOT_FOUND))
                .getId();
    }
}
