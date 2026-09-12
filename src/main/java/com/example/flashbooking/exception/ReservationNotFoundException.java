package com.example.flashbooking.exception;

import java.util.UUID;

/** Reserva inexistente. */
public class ReservationNotFoundException extends ResourceNotFoundException {

    public static final String ERROR_CODE = "RESERVATION_NOT_FOUND";

    public ReservationNotFoundException(UUID reservationId) {
        super(ERROR_CODE, "Reserva não encontrada: %s".formatted(reservationId));
    }
}
