package com.example.flashbooking.notification;

import com.example.flashbooking.entity.Reservation;
import java.time.Instant;
import java.util.UUID;

/**
 * O aviso enviado ao serviço externo de notificação quando uma reserva é criada.
 *
 * <p>{@code reservationId} não é apenas um dado do corpo: é também a <strong>chave de
 * entrega</strong>, enviada no header {@code Idempotency-Key}. É ela que torna a
 * retentativa segura — o destinatário reconhece a repetição e não avisa o cliente duas
 * vezes. Retry sem chave de idempotência não é resiliência, é duplicação automatizada.
 */
public record ReservationNotification(
        UUID reservationId,
        UUID eventId,
        int quantity,
        Instant expiresAt) {

    public static ReservationNotification of(Reservation reservation) {
        return new ReservationNotification(
                reservation.getId(),
                reservation.getEventId(),
                reservation.getQuantity(),
                reservation.getExpiresAt());
    }
}
