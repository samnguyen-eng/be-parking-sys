package com.parking.api.security;

import com.parking.api.exception.BusinessException;
import com.parking.api.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetails;

public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Long resolveUserId(UserDetails userDetails, UserRepository userRepository) {
        if (userDetails instanceof ParkingUserPrincipal principal) {
            return principal.getUserId();
        }
        return userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new BusinessException(
                        "User not found: " + userDetails.getUsername(), HttpStatus.NOT_FOUND))
                .getId();
    }
}
