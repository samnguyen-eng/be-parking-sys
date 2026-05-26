package com.parking.outbox.service;

import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import com.parking.outbox.entity.OutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Publishes outbox events to Google Cloud Pub/Sub.
 *
 * This service always publishes to Pub/Sub.
 * In local development, configure Pub/Sub emulator host so publish still works.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PubSubPublisherService {

    @Value("${app.pubsub.topic:reservation.created}")
    private String topic;

    private final PubSubTemplate pubSubTemplate;

    public void publish(OutboxEvent event) {
        log.debug("Publishing event id={} to topic={}", event.getId(), topic);
        pubSubTemplate.publish(topic, event.getPayload());
        log.info("Published event id={} type={} to topic={}", event.getId(), event.getEventType(), topic);
    }
}
