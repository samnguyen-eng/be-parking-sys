package com.parking.outbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ParkingOutboxApplication {

    public static void main(String[] args) {
        SpringApplication.run(ParkingOutboxApplication.class, args);
    }
}
