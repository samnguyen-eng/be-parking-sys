package com.parking.worker.controller;

import com.parking.worker.dto.PubSubPushRequest;
import com.parking.worker.service.ReservationWorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Receives Pub/Sub push messages from Cloud Pub/Sub.
 *
 * Cloud Pub/Sub push subscription POSTs to this endpoint.
 * Always return HTTP 200 to acknowledge; return 5xx to nack (Pub/Sub will retry).
 */
@RestController
@RequestMapping("/internal/pubsub")
@Slf4j
@RequiredArgsConstructor
public class PubSubPushController {

    private final ReservationWorkerService workerService;

    /**
     * POST /internal/pubsub/push
     *
     * Decodes the base64-encoded Pub/Sub message data and delegates to the worker service.
     * Returns 200 OK to acknowledge, or propagates exceptions (resulting in 500) to nack.
     */
    @PostMapping("/push")
    public ResponseEntity<Void> handlePush(@RequestBody PubSubPushRequest request) {
        PubSubPushRequest.Message msg = request.getMessage();
        if (msg == null || msg.getData() == null) {
            log.warn("Received Pub/Sub push with null message or data — acknowledging without processing.");
            return ResponseEntity.ok().build();
        }

        String messageId = msg.getMessageId() != null ? msg.getMessageId() : "unknown";
        String decodedPayload;
        try {
            byte[] decoded = Base64.getDecoder().decode(msg.getData());
            decodedPayload = new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.error("Failed to base64-decode message id={}: {}", messageId, e.getMessage());
            // Acknowledge to avoid infinite retries on a malformed message
            return ResponseEntity.ok().build();
        }

        log.debug("Received Pub/Sub push message id={} payload={}", messageId, decodedPayload);
        workerService.process(messageId, decodedPayload);

        return ResponseEntity.ok().build();
    }
}
