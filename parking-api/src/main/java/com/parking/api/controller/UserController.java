package com.parking.api.controller;

import com.parking.api.dto.response.ApiResponse;
import com.parking.api.dto.response.UserInfoResponse;
import com.parking.api.service.UserInfoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserInfoService userInfoService;

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<UserInfoResponse>> getCurrentUser(
            @AuthenticationPrincipal UserDetails userDetails) {
        UserInfoResponse response = userInfoService.getCurrentUserInfo(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
