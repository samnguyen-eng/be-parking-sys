package com.parking.worker.service;

public class OutOfOrderReservationException extends RuntimeException {

    public OutOfOrderReservationException(String message) {
        super(message);
    }
}
