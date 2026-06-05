package com.parking.worker.repository;

import com.parking.worker.entity.Reservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Reservation> findById(Long id);

    @Query(value = """
            SELECT * FROM reservations r
            WHERE r.space_id = :spaceId
              AND r.reservation_date = :reservationDate
              AND r.status = :status
              AND r.is_deleted = false
            ORDER BY r.created_at ASC, r.id ASC
            LIMIT 1
            FOR UPDATE
            """, nativeQuery = true)
    Optional<Reservation> findFirstPendingForSpaceAndDate(
            @Param("spaceId") Long spaceId,
            @Param("reservationDate") LocalDate reservationDate,
            @Param("status") String status
    );

    @Query("""
            SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END
            FROM Reservation r
            WHERE r.spaceId = :spaceId
              AND r.status = com.parking.worker.entity.ReservationStatus.CONFIRMED
              AND r.id <> :excludeId
              AND r.isDeleted = false
            """)
    boolean existsConfirmedForSpaceExcluding(
            @Param("spaceId") Long spaceId,
            @Param("excludeId") Long excludeId);
}
