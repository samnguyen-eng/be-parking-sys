package com.parking.api.dto.request;

import jakarta.validation.constraints.FutureOrPresent;
import lombok.Data;

import java.time.LocalDate;

@Data
public class ReserveRequest {

    // Optional: override plate number from user profile
    private String plateNumber;

    // Optional: defaults to today if null
    @FutureOrPresent(message = "Reservation date must be today or in the future")
    private LocalDate reservationDate;
}
