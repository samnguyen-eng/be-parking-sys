package com.parking.outbox.service;

import com.parking.outbox.entity.OutboxEvent;
import com.parking.outbox.entity.OutboxStatus;
import com.parking.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxPollerService {

    private static final int BATCH_SIZE = 50;
    private static final int MAX_RETRY = 5;

    private final OutboxEventRepository outboxEventRepository;
    private final PubSubPublisherService pubSubPublisherService;

    /**
     * Polls for PENDING outbox events every 500ms and publishes them to Pub/Sub.
     * On publish failure, increments retryCount. After MAX_RETRY failures, marks event DEAD.
     */
    @Scheduled(fixedDelay = 500)
    @Transactional
    public void pollAndPublish() {
        List<OutboxEvent> pendingEvents =
                outboxEventRepository.findPendingEvents(PageRequest.of(0, BATCH_SIZE));

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("Polling outbox: found {} PENDING event(s)", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            try {
                pubSubPublisherService.publish(event);
                event.setStatus(OutboxStatus.PUBLISHED);
                log.info("Outbox event id={} marked PUBLISHED", event.getId());
            } catch (Exception e) {
                int newRetryCount = event.getRetryCount() + 1;
                event.setRetryCount(newRetryCount);
                if (newRetryCount > MAX_RETRY) {
                    event.setStatus(OutboxStatus.DEAD);
                    log.error("Outbox event id={} marked DEAD after {} retries.",
                            event.getId(), newRetryCount, e);
                } else {
                    log.warn("Outbox event id={} publish failed (retry {}/{}).",
                            event.getId(), newRetryCount, MAX_RETRY, e);
                }
            }
            outboxEventRepository.save(event);
        }
    }
}
