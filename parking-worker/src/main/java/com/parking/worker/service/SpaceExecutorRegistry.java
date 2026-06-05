package com.parking.worker.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Manages a dedicated single-thread Platform Thread executor per parking spaceId.
 *
 * <p>Uses Platform Threads (not Virtual Threads) because Redisson distributed locks
 * track ownership by thread-id. Virtual Threads can change carrier thread between
 * suspensions, causing IllegalMonitorStateException when unlocking.
 *
 * <p>This ensures that all messages for a given space are processed
 * sequentially within a single Cloud Run instance, preserving FCFS ordering
 * without blocking the HTTP request thread.
 *
 * <p>Cross-instance ordering is handled by the per-message Redis processing lock
 * in {@link ReservationWorkerService} and the DB-level FCFS guard.
 */
@Component
@Slf4j
public class SpaceExecutorRegistry {

    /**
     * One single-threaded Platform Thread executor per spaceId.
     * ConcurrentHashMap + computeIfAbsent ensures at-most-one executor created per key.
     */
    private final ConcurrentHashMap<Long, ExecutorService> executors = new ConcurrentHashMap<>();

    /**
     * Returns the executor for the given spaceId, creating one if it does not yet exist.
     * Uses Platform Thread factory — stable thread-id required for Redisson lock correctness.
     */
    public ExecutorService getOrCreate(Long spaceId) {
        return executors.computeIfAbsent(spaceId, id ->
                Executors.newSingleThreadExecutor(
                        Thread.ofPlatform()
                              .name("space-" + id + "-worker-", 0)
                              .factory()
                )
        );
    }

    /**
     * Gracefully shuts down all executors on application shutdown.
     * Waits up to 30 seconds for in-flight tasks to complete.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down {} space executors...", executors.size());
        List<ExecutorService> all = new ArrayList<>(executors.values());
        executors.clear();

        for (ExecutorService executor : all) {
            executor.shutdown();
        }

        for (ExecutorService executor : all) {
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("All space executors shut down.");
    }
}
