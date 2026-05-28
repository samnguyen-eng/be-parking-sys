package com.parking.worker.service;

import com.parking.worker.entity.WorkerMessageRetry;
import com.parking.worker.entity.WorkerMessageRetryStatus;
import com.parking.worker.repository.WorkerMessageRetryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Slf4j
@RequiredArgsConstructor
public class WorkerMessageRetryService {

    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final int BACKOFF_BASE_SECONDS = 10;
    private static final int BACKOFF_MAX_SECONDS = 300;
    private static final int JITTER_MAX_SECONDS = 10;

    private final WorkerMessageRetryRepository retryRepository;
    private final ReservationWorkerService reservationWorkerService;
    private final ObjectMapper objectMapper;

    @Transactional
    public void recordFailure(String messageId, String payload, Exception exception) {
        String retryKey = resolveRetryKey(payload, messageId);
        final LocalDateTime now = LocalDateTime.now();
        final String errorMessage = safeErrorMessage(exception);
        WorkerMessageRetry retry = retryRepository.findByRetryKey(retryKey)
                .orElseGet(() -> WorkerMessageRetry.builder()
                        .retryKey(retryKey)
                        .messageId(messageId)
                        .payload(payload)
                        .retryCount(0)
                        .status(WorkerMessageRetryStatus.RETRYING)
                        .nextRetryAt(now)
                        .firstFailedAt(now)
                        .lastFailedAt(now)
                        .build());

        int nextRetryCount = retry.getRetryCount() + 1;
        retry.setRetryCount(nextRetryCount);
        retry.setPayload(payload);
        retry.setLastError(errorMessage);
        retry.setLastFailedAt(now);
        retry.setNextRetryAt(now.plusSeconds(calculateBackoffSeconds(nextRetryCount)));
        retry.setUpdatedBy("worker");

        boolean reachedMaxRetry = nextRetryCount >= MAX_RETRY_ATTEMPTS;
        if (reachedMaxRetry) {
            String compensationError = null;
            try {
                reservationWorkerService.compensateOnMaxRetry(payload, errorMessage);
            } catch (Exception compensateEx) {
                compensationError = safeErrorMessage(compensateEx);
                log.error("Compensation failed for retry key={}: {}", retryKey, compensationError, compensateEx);
            }
            retry.setStatus(WorkerMessageRetryStatus.DLQ);
            retry.setMovedToDlqAt(now);
            retry.setNextRetryAt(now);
            if (compensationError != null) {
                retry.setLastError(errorMessage + " | compensationError=" + compensationError);
            }
        } else {
            retry.setStatus(WorkerMessageRetryStatus.RETRYING);
        }
        retryRepository.save(retry);

        if (reachedMaxRetry) {
            log.error("Retry key={} reached max retry={} and marked as DLQ. lastError={}",
                    retryKey, MAX_RETRY_ATTEMPTS, errorMessage);
            return;
        }
        log.warn("Retry key={} failed attempt {}/{}. Scheduled retry at {}. lastError={}",
                retryKey, nextRetryCount, MAX_RETRY_ATTEMPTS, retry.getNextRetryAt(), errorMessage);
    }

    @Transactional
    public void clearOnSuccess(String payload, String fallbackMessageId) {
        String retryKey = resolveRetryKey(payload, fallbackMessageId);
        retryRepository.deleteByRetryKey(retryKey);
    }

    private String safeErrorMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    private String resolveRetryKey(String payload, String fallbackMessageId) {
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

    private long calculateBackoffSeconds(int retryCount) {
        long exponential = (long) BACKOFF_BASE_SECONDS << Math.max(0, retryCount - 1);
        long capped = Math.min(BACKOFF_MAX_SECONDS, exponential);
        int jitter = ThreadLocalRandom.current().nextInt(JITTER_MAX_SECONDS + 1);
        return capped + jitter;
    }

}
