package com.parking.worker.config;

import com.parking.worker.service.ReservationWorkerService;
import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class PubSubSubscriberConfig {

    private final PubSubTemplate pubSubTemplate;
    private final ReservationWorkerService reservationWorkerService;

    @Value("${app.pubsub.subscription:reservation-created-sub}")
    private String subscription;

    @PostConstruct
    public void subscribe() {
        pubSubTemplate.subscribe(subscription, message -> {
            String messageId = message.getPubsubMessage().getMessageId();
            String payload = message.getPubsubMessage().getData().toStringUtf8();
            try {
                reservationWorkerService.process(messageId, payload);
                message.ack();
            } catch (Exception ex) {
                log.error("Worker failed for message id={}: {}", messageId, ex.getMessage(), ex);
                message.nack();
            }
        });
        log.info("Subscribed worker to Pub/Sub subscription={}", subscription);
    }
}
