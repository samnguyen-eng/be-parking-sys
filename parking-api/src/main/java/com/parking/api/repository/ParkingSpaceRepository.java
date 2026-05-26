package com.parking.api.repository;

import com.parking.api.entity.ParkingSpace;
import com.parking.api.entity.ParkingSpaceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ParkingSpaceRepository extends JpaRepository<ParkingSpace, Long> {

    List<ParkingSpace> findAllByOrderBySpaceNumberAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ParkingSpace p WHERE p.status = 'AVAILABLE' ORDER BY p.spaceNumber ASC")
    List<ParkingSpace> findAllAvailableForUpdate();

    default Optional<ParkingSpace> findFirstAvailable() {
        List<ParkingSpace> spaces = findAllAvailableForUpdate();
        return spaces.isEmpty() ? Optional.empty() : Optional.of(spaces.get(0));
    }

    long countByStatus(ParkingSpaceStatus status);
}
