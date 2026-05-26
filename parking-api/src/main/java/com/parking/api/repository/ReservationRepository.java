package com.parking.api.repository;

import com.parking.api.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    boolean existsByUserIdAndReservationDate(Long userId, LocalDate date);

    Optional<Reservation> findByUserIdAndReservationDate(Long userId, LocalDate date);

    /**
     * Fetch all reservations for a given date, eagerly loading space and user
     * so ParkingService can build the spaceId -> plateNumber map efficiently.
     */
    @Query("SELECT r FROM Reservation r " +
           "JOIN FETCH r.user u " +
           "JOIN FETCH r.space s " +
           "WHERE r.reservationDate = :date " +
           "AND r.status <> com.parking.api.entity.ReservationStatus.CANCELLED")
    List<Reservation> findActiveByReservationDate(@Param("date") LocalDate date);
}
