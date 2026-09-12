package com.example.flashbooking.service;

import com.example.flashbooking.dto.response.ReservationResponse;

/**
 * Resultado da criação de uma reserva, distinguindo o que foi criado agora do que é repetição
 * de uma requisição anterior.
 *
 * <p>A distinção existe para que o controller não minta no status HTTP: responder
 * {@code 201 Created} a uma retentativa afirmaria que algo foi criado quando nada foi.
 */
public record ReservationCreationResult(ReservationResponse reservation, boolean replayed) {

    public static ReservationCreationResult created(ReservationResponse reservation) {
        return new ReservationCreationResult(reservation, false);
    }

    public static ReservationCreationResult replayed(ReservationResponse reservation) {
        return new ReservationCreationResult(reservation, true);
    }
}
