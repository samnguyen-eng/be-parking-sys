package com.parking.api.dto.response;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParkingSpaceResponse {

    private Long id;
    private String spaceNumber;
    private String status;
    // Last 3 digits of plate number, null if AVAILABLE
    private String occupantPlate;
}
