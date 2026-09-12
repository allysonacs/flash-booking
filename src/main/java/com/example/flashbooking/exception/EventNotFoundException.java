package com.example.flashbooking.exception;

import java.util.UUID;

/** Evento inexistente ou já removido. */
public class EventNotFoundException extends ResourceNotFoundException {

    public static final String ERROR_CODE = "EVENT_NOT_FOUND";

    public EventNotFoundException(UUID eventId) {
        super(ERROR_CODE, "Evento não encontrado: %s".formatted(eventId));
    }
}
