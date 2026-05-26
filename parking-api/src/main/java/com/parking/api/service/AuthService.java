package com.parking.api.service;

import com.parking.api.dto.request.LoginRequest;
import com.parking.api.dto.request.RegisterRequest;
import com.parking.api.dto.response.AuthResponse;
import com.parking.api.entity.Account;
import com.parking.api.entity.User;
import com.parking.api.exception.BusinessException;
import com.parking.api.repository.AccountRepository;
import com.parking.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Check duplicates
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BusinessException("Username already exists: " + request.getUsername());
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("Email already registered: " + request.getEmail());
        }
        if (userRepository.existsByPlateNumber(request.getPlateNumber())) {
            throw new BusinessException("Plate number already registered: " + request.getPlateNumber());
        }

        // Create user
        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .plateNumber(request.getPlateNumber().toUpperCase())
                .build();
        user = userRepository.save(user);
        log.info("Registered new user: {}", user.getUsername());

        // Create account with zero balance
        Account account = Account.builder()
                .user(user)
                .balance(BigDecimal.ZERO)
                .build();
        accountRepository.save(account);

        String token = jwtService.generateToken(user.getUsername());

        return AuthResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .username(user.getUsername())
                .userId(user.getId())
                .build();
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BusinessException("Invalid username or password", HttpStatus.UNAUTHORIZED));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException("Invalid username or password", HttpStatus.UNAUTHORIZED);
        }

        log.info("User logged in: {}", user.getUsername());

        String token = jwtService.generateToken(user.getUsername());

        return AuthResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .username(user.getUsername())
                .userId(user.getId())
                .build();
    }
}
