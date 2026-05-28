package com.parking.api.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class UserReservationResponse {

    private Long id;
    private Long spaceId;
    private String spaceNumber;
    private String status;
    private LocalDate reservationDate;
    private BigDecimal amount;
    private LocalDateTime expiresAt;
}
