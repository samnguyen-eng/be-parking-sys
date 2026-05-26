package com.parking.worker.repository;

import com.parking.worker.entity.ParkingSpace;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ParkingSpaceRepository extends JpaRepository<ParkingSpace, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ParkingSpace p WHERE p.status = com.parking.worker.entity.ParkingSpaceStatus.AVAILABLE ORDER BY p.spaceNumber ASC")
    Optional<ParkingSpace> findFirstAvailable();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ParkingSpace p WHERE p.id = :id")
    Optional<ParkingSpace> findByIdForUpdate(@Param("id") Long id);
}
