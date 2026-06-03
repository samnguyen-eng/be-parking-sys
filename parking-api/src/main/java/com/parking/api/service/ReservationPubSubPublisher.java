package com.parking.api.service;

import com.google.protobuf.ByteString;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.pubsub.v1.PubsubMessage;
import com.google.api.core.ApiFuture;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Slf4j
@Service
public class ReservationPubSubPublisher {

    public static final String WARMUP_ATTR = "x-warmup";

    private final PubSubTemplate pubSubTemplate;
    private final Executor pubSubPublishExecutor;

    public ReservationPubSubPublisher(
            PubSubTemplate pubSubTemplate,
            @Qualifier("pubSubPublishExecutor") Executor pubSubPublishExecutor) {
        this.pubSubTemplate = pubSubTemplate;
        this.pubSubPublishExecutor = pubSubPublishExecutor;
    }

    @Value("${app.pubsub.topic:reservation.created}")
    private String topic;

    @Value("${app.pubsub.publish-timeout-seconds:5}")
    private int publishTimeoutSeconds;

    @Value("${app.pubsub.warmup-enabled:true}")
    private boolean warmupEnabled;

    @Value("${app.pubsub.enabled:true}")
    private boolean pubSubEnabled;

    @Value("${app.pubsub.ordering-enabled:false}")
    private boolean orderingEnabled;

    @Value("${spring.cloud.gcp.project-id:}")
    private String projectId;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!pubSubEnabled || !warmupEnabled) {
            log.info("Pub/Sub publisher warm-up skipped (enabled={}, warmup={})", pubSubEnabled, warmupEnabled);
            return;
        }
        log.info("Warming up Pub/Sub publisher for topic={}", topic);
        try {
            pubSubTemplate.publish(topic, "__warmup__", Map.of(WARMUP_ATTR, "true"))
                    .get(publishTimeoutSeconds, TimeUnit.SECONDS);
            log.info("Pub/Sub publisher warm-up completed for topic={}", topic);
        } catch (Exception ex) {
            log.warn("Pub/Sub publisher warm-up failed for topic={} (non-fatal): {}", topic, ex.getMessage());
        }
    }

    private Publisher orderedPublisher;

    @PostConstruct
    public void initOrderedPublisher() {
        if (!pubSubEnabled || !orderingEnabled) {
            return;
        }
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalStateException("spring.cloud.gcp.project-id must be set when message ordering is enabled.");
        }

        // Pub/Sub ordering requires enabling it on the Publisher client as well.
        String topicName = String.format("projects/%s/topics/%s", projectId, topic);
        try {
            orderedPublisher = Publisher.newBuilder(topicName).setEnableMessageOrdering(true).build();
            log.info("Ordered Pub/Sub Publisher initialized for topic={} (projectId={})", topic, projectId);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to initialize ordered Pub/Sub Publisher for topic=" + topic + ": " + ex.getMessage(),
                    ex
            );
        }
    }

    @PreDestroy
    public void shutdownOrderedPublisher() {
        if (orderedPublisher != null) {
            try {
                orderedPublisher.shutdown();
            } catch (Exception ex) {
                log.warn("Failed to shutdown ordered Pub/Sub publisher: {}", ex.getMessage(), ex);
            }
        }
    }

    /**
     * Fire-and-forget after DB commit — HTTP thread returns without waiting on Pub/Sub.
     */
    public void publishReservationCreatedAfterCommit(String payload, Long spaceId, LocalDate reservationDate) {
        pubSubPublishExecutor.execute(() -> {
            try {
                publishReservationCreated(payload, spaceId, reservationDate);
            } catch (Exception ex) {
                log.error("Async Pub/Sub publish failed spaceId={} date={}: {}",
                        spaceId, reservationDate, ex.getMessage());
            }
        });
    }

    public void publishReservationCreated(String payload, Long spaceId, LocalDate reservationDate) {
        if (!pubSubEnabled) {
            log.debug("Pub/Sub disabled; skipping publish payload={}", payload);
            return;
        }
        try {
            if (orderingEnabled) {
                String orderingKey = String.format("space:%d:%s", spaceId, reservationDate);
                PubsubMessage message = PubsubMessage.newBuilder()
                        .setData(ByteString.copyFromUtf8(payload))
                        .setOrderingKey(orderingKey)
                        .build();

                if (orderedPublisher == null) {
                    throw new IllegalStateException("Ordered publisher is not initialized.");
                }

                ApiFuture<String> future = orderedPublisher.publish(message);
                String messageId = future.get(publishTimeoutSeconds, TimeUnit.SECONDS);
                log.info("Published reservation.created to topic={} orderingKey={} messageId={} (ordered publisher)",
                        topic, orderingKey, messageId);
                return;
            }

            String messageId = pubSubTemplate.publish(topic, payload)
                    .get(publishTimeoutSeconds, TimeUnit.SECONDS);
            log.info("Published reservation.created to topic={} messageId={} (ordering disabled)",
                    topic, messageId);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to publish reservation event to topic=" + topic + ": " + ex.getMessage(), ex);
        }
    }
}
