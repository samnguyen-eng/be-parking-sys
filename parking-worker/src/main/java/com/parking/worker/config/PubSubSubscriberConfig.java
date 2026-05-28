package com.parking.worker.config;

import com.parking.worker.service.ReservationWorkerService;
import com.parking.worker.service.WorkerMessageRetryService;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class PubSubSubscriberConfig {

    private final PubSubTemplate pubSubTemplate;
    private final ReservationWorkerService reservationWorkerService;
    private final WorkerMessageRetryService workerMessageRetryService;

    @Value("${app.pubsub.subscription:reservation-created-sub}")
    private String subscription;

    private Subscriber subscriber;

    /** Attribute key published by the outbox warm-up. Messages carrying this are acked and skipped. */
    private static final String WARMUP_ATTR = "x-warmup";

    @PostConstruct
    public void subscribe() {
        subscriber = pubSubTemplate.subscribe(subscription, message -> {
            String messageId = message.getPubsubMessage().getMessageId();

            // Skip warm-up messages published by parking-outbox on startup
            if ("true".equals(message.getPubsubMessage().getAttributesOrDefault(WARMUP_ATTR, null))) {
                log.debug("Received warm-up message id={}, acking and skipping", messageId);
                message.ack();
                return;
            }

            String payload = message.getPubsubMessage().getData().toStringUtf8();
            log.info("Received Pub/Sub message id={} payload={}", messageId, payload);
            try {
                reservationWorkerService.process(messageId, payload);
                try {
                    workerMessageRetryService.clearOnSuccess(payload, messageId);
                } catch (Exception clearEx) {
                    log.warn("Process success but failed to clear retry record for message id={}: {}",
                            messageId, clearEx.getMessage(), clearEx);
                }
                message.ack();
                log.info("Worker processed message id={} successfully; acked", messageId);
            } catch (Exception ex) {
                log.error("Worker failed for message id={}: {}", messageId, ex.getMessage(), ex);
                try {
                    workerMessageRetryService.recordFailure(messageId, payload, ex);
                } catch (Exception retryEx) {
                    log.error("Failed to record retry for message id={}: {}", messageId, retryEx.getMessage(), retryEx);
                }
                message.nack();
                log.warn("Worker failed for message id={}; nacked for retry", messageId);
            }
        });
        // NOTE: pubSubTemplate.subscribe() already calls startAsync() internally.
        // DO NOT call subscriber.startAsync() again — it causes "already been started" crash.
        subscriber.addListener(new Subscriber.Listener() {
            @Override
            public void failed(Subscriber.State from, Throwable failure) {
                log.error("Pub/Sub subscriber failed from state={} for subscription={}", from, subscription, failure);
            }
        }, Runnable::run);
        log.info("Pub/Sub subscriber started for subscription={}", subscription);
    }

    @PreDestroy
    public void shutdown() {
        if (subscriber != null) {
            log.info("Stopping Pub/Sub subscriber for subscription={}", subscription);
            subscriber.stopAsync().awaitTerminated();
        }
    }
}
