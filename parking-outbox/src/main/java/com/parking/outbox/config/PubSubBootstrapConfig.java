package com.parking.outbox.config;

import com.google.api.gax.rpc.AlreadyExistsException;
import com.google.cloud.spring.pubsub.PubSubAdmin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class PubSubBootstrapConfig {

    private final PubSubAdmin pubSubAdmin;

    @Value("${spring.cloud.gcp.project-id}")
    private String projectId;

    @Value("${app.pubsub.topic:reservation.created}")
    private String topic;

    @Value("${app.pubsub.subscription:reservation-created-sub}")
    private String subscription;

    @Value("${app.pubsub.bootstrap-enabled:true}")
    private boolean bootstrapEnabled;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureTopicAndSubscription() {
        if (!bootstrapEnabled) {
            log.info("Pub/Sub bootstrap is disabled by app.pubsub.bootstrap-enabled=false");
            return;
        }

        String topicName = String.format("projects/%s/topics/%s", projectId, topic);
        String subscriptionName = String.format("projects/%s/subscriptions/%s", projectId, subscription);

        createTopicIfMissing(topicName);
        createSubscriptionIfMissing(topicName, subscriptionName);
    }

    private void createTopicIfMissing(String topicName) {
        try {
            pubSubAdmin.createTopic(topicName);
            log.info("Created Pub/Sub topic {}", topicName);
        } catch (AlreadyExistsException ex) {
            log.info("Pub/Sub topic already exists {}", topicName);
        } catch (Exception ex) {
            log.warn("Failed to create Pub/Sub topic {}. Continue startup without bootstrap.", topicName, ex);
        }
    }

    private void createSubscriptionIfMissing(String topicName, String subscriptionName) {
        try {
            pubSubAdmin.createSubscription(subscriptionName, topicName, 10);
            log.info("Created Pub/Sub subscription {} -> {}", subscriptionName, topicName);
        } catch (AlreadyExistsException ex) {
            log.info("Pub/Sub subscription already exists {}", subscriptionName);
        } catch (Exception ex) {
            log.warn(
                    "Failed to create Pub/Sub subscription {} for topic {}. Continue startup without bootstrap.",
                    subscriptionName,
                    topicName,
                    ex
            );
        }
    }
}
