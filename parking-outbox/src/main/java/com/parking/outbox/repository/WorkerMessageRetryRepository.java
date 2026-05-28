package com.parking.outbox.repository;

import com.parking.outbox.entity.WorkerMessageRetry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface WorkerMessageRetryRepository extends JpaRepository<WorkerMessageRetry, Long> {

    @Query(value = """
            SELECT *
            FROM worker_message_retries
            WHERE status = :status
              AND is_deleted = false
              AND next_retry_at <= :now
            ORDER BY next_retry_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<WorkerMessageRetry> claimDueRetries(
            @Param("status") String status,
            @Param("now") LocalDateTime now,
            @Param("limit") int limit
    );
}
