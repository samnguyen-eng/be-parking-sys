package com.parking.api.repository;

import com.parking.api.entity.ParkingSpace;
import com.parking.api.entity.ParkingSpaceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ParkingSpaceRepository extends JpaRepository<ParkingSpace, Long> {

    List<ParkingSpace> findAllByOrderBySpaceNumberAsc();

    @Query(value = "SELECT * FROM parking_spaces " +
                   "WHERE status = 'AVAILABLE' " +
                   "ORDER BY space_number ASC " +
                   "FOR UPDATE SKIP LOCKED " +
                   "LIMIT 1", nativeQuery = true)
    Optional<ParkingSpace> findFirstAvailable();

    @Query("SELECT p.id FROM ParkingSpace p WHERE p.status = com.parking.api.entity.ParkingSpaceStatus.AVAILABLE")
    List<Long> findAvailableSpaceIds();

    @Query(value = "SELECT * FROM parking_spaces " +
                   "WHERE id = :spaceId " +
                   "FOR UPDATE", nativeQuery = true)
    Optional<ParkingSpace> findByIdForUpdate(@Param("spaceId") Long spaceId);

    long countByStatus(ParkingSpaceStatus status);
}
