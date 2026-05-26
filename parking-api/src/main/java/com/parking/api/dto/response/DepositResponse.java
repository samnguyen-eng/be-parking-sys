package com.parking.api.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class DepositResponse {

    private Long accountId;
    private BigDecimal balance;
    private BigDecimal depositAmount;
    private String message;
}
