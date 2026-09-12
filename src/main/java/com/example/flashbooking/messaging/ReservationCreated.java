package com.example.flashbooking.messaging;

import com.example.flashbooking.entity.Reservation;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento de integração: uma reserva foi criada e os assentos estão comprometidos.
 *
 * <p>É um <strong>fato consumado</strong>, no passado, e não um pedido: quem o recebe não
 * decide nada sobre a reserva, apenas reage a ela. Por isso o evento carrega os dados de que
 * o consumidor precisa (quantidade, prazo) em vez de só um id — um consumidor que precisa
 * voltar à API para entender o evento recria o acoplamento que a mensageria deveria remover.
 *
 * <p>{@code messageId} é a identidade da <em>mensagem</em>, não da reserva, e é a chave de
 * deduplicação do consumidor. {@code eventId} é o id do <em>show</em> — o domínio chama de
 * evento o espetáculo, e é ele que serve de chave de partição.
 *
 * @param messageId     identidade da mensagem; igual ao id da linha no outbox
 * @param occurredAt    quando o fato aconteceu, segundo o produtor
 * @param reservationId a reserva criada
 * @param eventId       o show a que a reserva pertence
 * @param quantity      assentos comprometidos
 * @param expiresAt     prazo de validade da reserva
 */
public record ReservationCreated(
        UUID messageId,
        Instant occurredAt,
        UUID reservationId,
        UUID eventId,
        int quantity,
        Instant expiresAt) {

    /** Nome do tipo gravado no outbox e usado como header da mensagem. */
    public static final String TYPE = "ReservationCreated";

    public static ReservationCreated from(Reservation reservation, UUID messageId, Instant occurredAt) {
        return new ReservationCreated(
                messageId,
                occurredAt,
                reservation.getId(),
                reservation.getEventId(),
                reservation.getQuantity(),
                reservation.getExpiresAt());
    }
}
