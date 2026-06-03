package com.parking.worker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parking.worker.dto.PubSubPushRequest;
import com.parking.worker.service.ReservationWorkerService;
import com.parking.worker.service.SpaceExecutorRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Receives Pub/Sub push messages from Cloud Pub/Sub.
 *
 * <p>Messages are routed to a dedicated single-thread VirtualThread executor per spaceId.
 * This ensures that all messages for the same space are processed sequentially
 * within a single instance, preserving FCFS ordering.
 *
 * <p>Cross-instance duplicate handling is done by the per-message Redis processing lock
 * inside {@link ReservationWorkerService}.
 *
 * <p>Returns HTTP 200 to ACK, HTTP 5xx to NACK (Pub/Sub will retry).
 */
@RestController
@RequestMapping("/internal/pubsub")
@Slf4j
@RequiredArgsConstructor
public class PubSubPushController {

    private static final String WARMUP_ATTR = "x-warmup";

    private final ReservationWorkerService workerService;
    private final SpaceExecutorRegistry executorRegistry;
    private final ObjectMapper objectMapper;

    /**
     * Timeout for waiting on the executor task result.
     * Must be shorter than the Pub/Sub ack deadline (configured on subscription).
     * Default: 55 seconds (assuming 60s ack deadline).
     */
    @Value("${app.worker.task-timeout-seconds:55}")
    private int taskTimeoutSeconds;

    /**
     * POST /internal/pubsub/push
     *
     * 1. Decode base64 payload
     * 2. Skip warmup messages
     * 3. Parse spaceId and route to the dedicated executor for that space
     * 4. Wait for result → return 200 (ACK) or 500 (NACK)
     */
    @PostMapping("/push")
    public ResponseEntity<Void> handlePush(@RequestBody PubSubPushRequest request) {
        PubSubPushRequest.Message msg = request.getMessage();
        if (msg == null || msg.getData() == null) {
            log.warn("Received Pub/Sub push with null message or data — acknowledging without processing.");
            return ResponseEntity.ok().build();
        }

        // Skip warmup messages published by the API service on startup
        Map<String, String> attributes = msg.getAttributes();
        if (attributes != null && "true".equals(attributes.get(WARMUP_ATTR))) {
            log.info("Received warmup message — acknowledging without processing.");
            return ResponseEntity.ok().build();
        }

        String messageId = msg.getMessageId() != null ? msg.getMessageId() : "unknown";

        String payload;
        try {
            byte[] decoded = Base64.getDecoder().decode(msg.getData());
            payload = new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.error("Failed to base64-decode message id={}: {}", messageId, e.getMessage());
            // ACK to avoid infinite retries on a permanently malformed message
            return ResponseEntity.ok().build();
        }

        log.debug("Received Pub/Sub push message id={} payload={}", messageId, payload);

        // Parse spaceId to route to the correct executor
        long spaceId = parseSpaceId(payload);
        if (spaceId <= 0) {
            log.error("Invalid or missing spaceId in payload for message id={}. Payload={}", messageId, payload);
            // ACK to avoid infinite retries on a permanently malformed message
            return ResponseEntity.ok().build();
        }

        // Submit to the single-thread VirtualThread executor for this spaceId.
        // Messages for the same space queue up and are processed one at a time.
        ExecutorService executor = executorRegistry.getOrCreate(spaceId);
        Future<?> future = executor.submit(() -> workerService.process(messageId, payload));

        // Wait for the task to complete before ACK/NACK.
        // This preserves Pub/Sub retry semantics: failures result in 5xx → NACK → redeliver.
        try {
            future.get(taskTimeoutSeconds, TimeUnit.SECONDS);
            return ResponseEntity.ok().build();
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("Processing timed out for message id={} spaceId={}", messageId, spaceId);
            return ResponseEntity.internalServerError().build();
        } catch (ExecutionException e) {
            log.error("Processing failed for message id={} spaceId={}: {}", messageId, spaceId, e.getCause().getMessage(), e.getCause());
            return ResponseEntity.internalServerError().build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for message id={}", messageId);
            return ResponseEntity.internalServerError().build();
        }
    }

    private long parseSpaceId(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            return node.path("spaceId").asLong(0L);
        } catch (Exception e) {
            return 0L;
        }
    }
}
