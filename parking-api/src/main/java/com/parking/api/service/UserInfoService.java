package com.parking.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parking.api.dto.response.UserInfoResponse;
import com.parking.api.entity.User;
import com.parking.api.exception.ResourceNotFoundException;
import com.parking.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserInfoService {

    private static final String USER_INFO_CACHE_PREFIX = "user:info:";
    private static final long USER_INFO_TTL_SECONDS = 86400L;

    private final UserRepository userRepository;
    private final ReservationCacheGuard reservationCacheGuard;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public UserInfoResponse getCurrentUserInfo(String username) {
        String cacheKey = USER_INFO_CACHE_PREFIX + username;
        RBucket<String> bucket = redissonClient.getBucket(cacheKey);
        String cachedPayload = bucket.get();
        if (cachedPayload != null && !cachedPayload.isBlank()) {
            try {
                UserInfoResponse cached = objectMapper.readValue(cachedPayload, UserInfoResponse.class);
                if (cached.getUserId() != null && cached.getPlateNumber() != null) {
                    reservationCacheGuard.cacheUserPlate(cached.getUserId(), cached.getPlateNumber());
                }
                return cached;
            } catch (JsonProcessingException ex) {
                log.warn("Failed to deserialize user info cache for username={}", username, ex);
            }
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));

        UserInfoResponse response = UserInfoResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .plateNumber(user.getPlateNumber())
                .build();

        reservationCacheGuard.cacheUserPlate(user.getId(), user.getPlateNumber());

        try {
            bucket.set(objectMapper.writeValueAsString(response), USER_INFO_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize user info cache for username={}", username, ex);
        }
        return response;
    }
}
