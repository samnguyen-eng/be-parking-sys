package com.parking.api.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
public class ReserveResponse {

    private Long reservationId;
    private String status;
    private String message;
    private LocalDate reservationDate;
    private BigDecimal amount;
    private BigDecimal remainingBalance;
}
