package com.parking.api.dto.request;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.time.LocalDate;

@Data
public class ReserveRequest {

    @NotNull(message = "spaceId is required")
    @Positive(message = "spaceId must be greater than 0")
    private Long spaceId;

    // Optional: override plate number from user profile
    private String plateNumber;

    // Optional: defaults to today if null
    @FutureOrPresent(message = "Reservation date must be today or in the future")
    private LocalDate reservationDate;
}
