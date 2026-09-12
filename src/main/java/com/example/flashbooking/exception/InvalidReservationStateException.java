package com.example.flashbooking.exception;

import com.example.flashbooking.entity.ReservationStatus;
import java.util.UUID;

/** A operação não é válida para o estado atual da reserva. */
public class InvalidReservationStateException extends DomainException {

    public static final String ERROR_CODE = "INVALID_RESERVATION_STATE";

    public InvalidReservationStateException(UUID reservationId, ReservationStatus status, String operation) {
        super(ERROR_CODE, "Não é possível %s a reserva %s no estado %s"
                .formatted(operation, reservationId, status));
    }
}
