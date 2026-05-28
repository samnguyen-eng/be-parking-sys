package com.parking.worker.repository;

import com.parking.worker.entity.WorkerMessageRetry;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WorkerMessageRetryRepository extends JpaRepository<WorkerMessageRetry, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<WorkerMessageRetry> findByRetryKey(String retryKey);

    void deleteByRetryKey(String retryKey);
}
