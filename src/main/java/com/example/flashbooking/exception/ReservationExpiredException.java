package com.example.flashbooking.exception;

import java.util.UUID;

/**
 * A confirmação chegou depois do prazo da reserva.
 *
 * <p>É diferente de {@link InvalidReservationStateException} de propósito. Aqui a reserva
 * ainda está gravada como {@code PENDING}: o prazo venceu, mas a varredura de expiração — que
 * roda em intervalo, e não no instante exato do vencimento — ainda não passou por ela. Para o
 * cliente o desfecho é o mesmo de uma reserva já expirada, e responder "estado inválido:
 * PENDING" seria uma contradição difícil de interpretar de fora.
 *
 * <p>O código {@code RESERVATION_EXPIRED} deixa a UI distinguir "você demorou" de "esta
 * reserva foi cancelada", que são duas histórias diferentes para quem está comprando.
 */
public class ReservationExpiredException extends DomainException {

    public static final String ERROR_CODE = "RESERVATION_EXPIRED";

    public ReservationExpiredException(UUID reservationId) {
        super(ERROR_CODE, "O prazo da reserva %s venceu; ela não pode mais ser confirmada"
                .formatted(reservationId));
    }
}
