package com.parking.outbox.service;

import com.parking.outbox.entity.WorkerMessageRetry;
import com.parking.outbox.entity.WorkerMessageRetryStatus;
import com.parking.outbox.repository.WorkerMessageRetryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerRetryRepublishService {

    private static final int RETRY_BATCH_SIZE = 20;

    private final WorkerMessageRetryRepository retryRepository;
    private final PubSubPublisherService pubSubPublisherService;

    @Scheduled(fixedDelay = 10000)
    @Transactional
    public void republishDueRetries() {
        List<WorkerMessageRetry> dueRetries = retryRepository.claimDueRetries(
                WorkerMessageRetryStatus.RETRYING.name(),
                LocalDateTime.now(),
                RETRY_BATCH_SIZE
        );
        if (dueRetries.isEmpty()) {
            return;
        }

        for (WorkerMessageRetry retry : dueRetries) {
            try {
                pubSubPublisherService.publishRawPayload(retry.getPayload());
                retry.setNextRetryAt(LocalDateTime.now().plusSeconds(60));
                retry.setUpdatedBy("outbox");
                retryRepository.save(retry);
                log.info("Republished retryKey={} retryCount={} nextRetryAt={}",
                        retry.getRetryKey(), retry.getRetryCount(), retry.getNextRetryAt());
            } catch (Exception ex) {
                log.error("Failed to republish retryKey={}: {}", retry.getRetryKey(), ex.getMessage(), ex);
                retry.setLastError("Republish failed: " + safeMessage(ex));
                retry.setLastFailedAt(LocalDateTime.now());
                retry.setUpdatedBy("outbox");
                retryRepository.save(retry);
            }
        }
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
