package com.parking.api.service.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ReservationCreatedResult(
        Long reservationId,
        String status,
        Long spaceId,
        String spaceNumber,
        LocalDate reservationDate,
        BigDecimal amount,
        BigDecimal remainingBalance,
        String pubSubPayload
) {
}
