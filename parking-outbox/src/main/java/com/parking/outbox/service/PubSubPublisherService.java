package com.parking.outbox.service;

import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import com.parking.outbox.entity.OutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Publishes outbox events to Google Cloud Pub/Sub.
 *
 * Warm-up strategy:
 *   On ApplicationReadyEvent, a dummy message with attribute x-warmup=true is published.
 *   This pre-establishes the gRPC channel and TLS negotiation so the first real publish
 *   does not incur cold-start latency (~30s). Workers must ack and skip messages that carry
 *   this attribute.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PubSubPublisherService {

    /** Attribute key used to mark warm-up messages. Workers must skip these. */
    public static final String WARMUP_ATTR = "x-warmup";

    @Value("${app.pubsub.topic:reservation.created}")
    private String topic;

    // 60s safety-net for the very first publish (gRPC cold-start).
    // After warm-up completes at startup, real publishes are fast (< 1s).
    @Value("${app.pubsub.publish-timeout-seconds:60}")
    private int publishTimeoutSeconds;

    @Value("${app.pubsub.warmup-enabled:true}")
    private boolean warmupEnabled;

    private final PubSubTemplate pubSubTemplate;

    /**
     * Pre-warms the Pub/Sub gRPC channel on startup.
     * Runs after ApplicationReadyEvent so all beans (including PubSubAdmin bootstrap) are ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!warmupEnabled) {
            log.info("Pub/Sub publisher warm-up disabled (app.pubsub.warmup-enabled=false)");
            return;
        }
        log.info("Warming up Pub/Sub publisher gRPC channel for topic={}", topic);
        try {
            pubSubTemplate.publish(topic, "__warmup__", Map.of(WARMUP_ATTR, "true"))
                    .get(publishTimeoutSeconds, TimeUnit.SECONDS);
            log.info("Pub/Sub publisher warm-up completed for topic={}", topic);
        } catch (Exception ex) {
            // Warm-up failure is non-fatal — log and continue.
            // Real publishes will still work; they may just be slower on the first call.
            log.warn("Pub/Sub publisher warm-up failed for topic={} (non-fatal): {}", topic, ex.getMessage());
        }
    }

    public void publish(OutboxEvent event) {
        log.debug("Publishing event id={} to topic={}", event.getId(), topic);
        try {
            String messageId = pubSubTemplate.publish(topic, event.getPayload())
                    .get(publishTimeoutSeconds, TimeUnit.SECONDS);
            log.info("Published event id={} type={} to topic={} messageId={}",
                    event.getId(), event.getEventType(), topic, messageId);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    String.format("Failed to publish outbox event id=%s to topic=%s", event.getId(), topic),
                    ex
            );
        }
    }

    public void publishRawPayload(String payload) {
        pubSubTemplate.publish(topic, payload);
        log.info("Republished retry payload to topic={}", topic);
    }
}
