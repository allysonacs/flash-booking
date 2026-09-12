package com.example.flashbooking.exception;

import com.example.flashbooking.entity.EventStatus;
import java.util.UUID;

/** O evento existe, mas não está aceitando reservas. */
public class EventNotOpenForReservationException extends DomainException {

    public static final String ERROR_CODE = "EVENT_NOT_OPEN_FOR_RESERVATION";

    public EventNotOpenForReservationException(UUID eventId, EventStatus status) {
        super(ERROR_CODE, "O evento %s não aceita reservas no estado %s".formatted(eventId, status));
    }
}
