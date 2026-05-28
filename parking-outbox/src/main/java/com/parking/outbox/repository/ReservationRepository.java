package com.parking.outbox.repository;

import com.parking.outbox.entity.Reservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r " +
           "WHERE r.expiresAt <= :now " +
           "AND r.status IN (com.parking.outbox.entity.ReservationStatus.PENDING, com.parking.outbox.entity.ReservationStatus.CONFIRMED) " +
           "AND r.isDeleted = false")
    List<Reservation> findExpiredForUpdate(@Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Reservation> findById(Long id);
}
