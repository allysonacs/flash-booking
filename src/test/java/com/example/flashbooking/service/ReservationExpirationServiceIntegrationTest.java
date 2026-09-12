package com.example.flashbooking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.flashbooking.dto.request.CreateEventRequest;
import com.example.flashbooking.dto.request.CreateReservationRequest;
import com.example.flashbooking.exception.ReservationExpiredException;
import com.example.flashbooking.repository.ReservationRepository;
import com.example.flashbooking.support.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A regra da expiração, provada contra o PostgreSQL.
 *
 * <p>Aqui a varredura é invocada diretamente, e não pelo agendador: o que se verifica é
 * <em>o que acontece dentro de um lote</em> — a transição de estado, a devolução dos
 * assentos e o que a varredura deliberadamente não toca — cancelada, confirmada, ou ainda
 * dentro do prazo. O agendamento em si é provado por
 * {@code ReservationExpirationSchedulingIntegrationTest}, e a disputa entre instâncias por
 * {@code ReservationExpirationConcurrencyIntegrationTest}.
 *
 * <p>O vencimento é forçado por {@code UPDATE} direto em {@code expires_at} em vez de
 * {@code Thread.sleep} sobre o TTL real: o teste passa a ser instantâneo e determinístico, e
 * o corte continua sendo avaliado pelo {@code now()} do banco, exatamente como em produção.
 */
class ReservationExpirationServiceIntegrationTest extends AbstractIntegrationTest {

    private static final int BATCH_SIZE = 50;

    @Autowired
    private EventService eventService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationExpirationService expirationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("reserva vencida transiciona PENDING → EXPIRED")
    void dueReservationBecomesExpired() {
        UUID eventId = createEvent(10);
        UUID reservationId = reserve(eventId, 3);

        forceDue(reservationId);
        int expired = expirationService.expireDueReservations(BATCH_SIZE);

        assertThat(expired).isEqualTo(1);
        assertThat(statusOf(reservationId)).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("os ingressos da reserva expirada voltam para a disponibilidade do evento")
    void expiredReservationReturnsSeatsToInventory() {
        UUID eventId = createEvent(10);
        UUID reservationId = reserve(eventId, 4);
        assertThat(reservedCount(eventId)).isEqualTo(4);
        assertThat(availableCapacity(eventId)).isEqualTo(6);

        forceDue(reservationId);
        expirationService.expireDueReservations(BATCH_SIZE);

        assertThat(reservedCount(eventId)).isZero();
        assertThat(availableCapacity(eventId)).isEqualTo(10);

        // Os assentos devolvidos voltam a ser vendáveis — devolver ao contador sem que o
        // evento volte a vendê-los seria o mesmo que não devolver.
        assertThat(reserve(eventId, 10)).isNotNull();
        assertThat(availableCapacity(eventId)).isZero();
    }

    @Test
    @DisplayName("reserva ainda dentro do prazo não é tocada")
    void reservationWithinItsDeadlineIsLeftAlone() {
        UUID eventId = createEvent(10);
        UUID reservationId = reserve(eventId, 2);

        int expired = expirationService.expireDueReservations(BATCH_SIZE);

        assertThat(expired).isZero();
        assertThat(statusOf(reservationId)).isEqualTo("PENDING");
        assertThat(reservedCount(eventId)).isEqualTo(2);
    }

    @Test
    @DisplayName("reserva já cancelada não expira, e o assento não volta duas vezes")
    void cancelledReservationIsNeverExpired() {
        UUID eventId = createEvent(10);
        UUID reservationId = reserve(eventId, 6);

        reservationService.cancel(reservationId);
        assertThat(reservedCount(eventId)).isZero();

        // Mesmo vencida, uma reserva cancelada está fora do alcance da varredura: a
        // reivindicação só enxerga PENDING.
        forceDue(reservationId);
        int expired = expirationService.expireDueReservations(BATCH_SIZE);

        assertThat(expired).isZero();
        assertThat(statusOf(reservationId)).isEqualTo("CANCELLED");
        assertThat(reservedCount(eventId)).isZero();
        assertThat(availableCapacity(eventId)).isEqualTo(10);
    }

    @Test
    @DisplayName("reserva confirmada não expira, e os ingressos continuam vendidos")
    void confirmedReservationIsNeverExpired() {
        UUID eventId = createEvent(10);
        UUID reservationId = reserve(eventId, 6);

        reservationService.confirm(reservationId);
        assertThat(reservedCount(eventId)).isEqualTo(6);

        // Confirmada, a reserva sai do alcance da varredura pelo mesmo motivo que a
        // cancelada: a reivindicação só enxerga PENDING. A diferença é o efeito — aqui os
        // assentos ficam vendidos, e devolvê-los seria vender o mesmo lugar duas vezes.
        forceDue(reservationId);
        int expired = expirationService.expireDueReservations(BATCH_SIZE);

        assertThat(expired).isZero();
        assertThat(statusOf(reservationId)).isEqualTo("CONFIRMED");
        assertThat(reservedCount(eventId)).isEqualTo(6);
        assertThat(availableCapacity(eventId)).isEqualTo(4);
    }

    @Test
    @DisplayName("confirmação que chega depois do prazo é recusada, mesmo antes da varredura")
    void confirmationAfterTheDeadlineIsRefusedBeforeTheSweep() {
        UUID eventId = createEvent(10);
        UUID reservationId = reserve(eventId, 3);

        // A janela exata que a guarda de prazo fecha: o TTL venceu e a varredura ainda não
        // passou, então a reserva continua PENDING no banco. Aceitar a confirmação aqui a
        // tiraria de PENDING, a varredura nunca mais a encontraria, e o prazo teria sido
        // atravessado — os assentos ficariam vendidos para quem perdeu a hora.
        forceDue(reservationId);
        assertThat(statusOf(reservationId)).isEqualTo("PENDING");

        assertThatThrownBy(() -> reservationService.confirm(reservationId))
                .isInstanceOf(ReservationExpiredException.class);

        assertThat(statusOf(reservationId)).isEqualTo("PENDING");

        // E a varredura segue dona da reserva: ela expira e devolve os assentos normalmente.
        assertThat(expirationService.expireDueReservations(BATCH_SIZE)).isEqualTo(1);
        assertThat(statusOf(reservationId)).isEqualTo("EXPIRED");
        assertThat(availableCapacity(eventId)).isEqualTo(10);
    }

    @Test
    @DisplayName("reserva já expirada não é processada de novo em uma segunda varredura")
    void alreadyExpiredReservationIsNotProcessedTwice() {
        UUID eventId = createEvent(10);
        UUID reservationId = reserve(eventId, 5);
        forceDue(reservationId);

        assertThat(expirationService.expireDueReservations(BATCH_SIZE)).isEqualTo(1);
        assertThat(reservedCount(eventId)).isZero();

        // Idempotência do processamento: a varredura pode rodar quantas vezes quiser — em
        // todas as instâncias, a qualquer intervalo — sem devolver o mesmo assento de novo.
        assertThat(expirationService.expireDueReservations(BATCH_SIZE)).isZero();
        assertThat(expirationService.expireDueReservations(BATCH_SIZE)).isZero();

        assertThat(statusOf(reservationId)).isEqualTo("EXPIRED");
        assertThat(reservedCount(eventId)).isZero();
        assertThat(availableCapacity(eventId)).isEqualTo(10);
    }

    @Test
    @DisplayName("a transição guardada por estado aplica-se uma única vez")
    void guardedTransitionAppliesOnlyOnce() {
        UUID eventId = createEvent(10);
        UUID reservationId = reserve(eventId, 1);
        forceDue(reservationId);

        List<UUID> batch = List.of(reservationId);

        // É esta guarda — `AND status = 'PENDING'` no UPDATE — que torna a transição
        // idempotente mesmo se o lote for reprocessado sem o lock da reivindicação. Cada
        // chamada roda em sua própria transação, como aconteceria em duas varreduras.
        assertThat(inItsOwnTransaction(batch)).isEqualTo(1);
        assertThat(inItsOwnTransaction(batch)).isZero();
    }

    @Test
    @DisplayName("a varredura respeita o tamanho do lote e drena o resto na passada seguinte")
    void sweepHonoursTheBatchSize() {
        UUID eventId = createEvent(20);
        for (int i = 0; i < 7; i++) {
            forceDue(reserve(eventId, 1));
        }
        assertThat(reservedCount(eventId)).isEqualTo(7);

        assertThat(expirationService.expireDueReservations(3)).isEqualTo(3);
        assertThat(reservedCount(eventId)).isEqualTo(4);

        assertThat(expirationService.expireDueReservations(3)).isEqualTo(3);
        assertThat(expirationService.expireDueReservations(3)).isEqualTo(1);
        assertThat(expirationService.expireDueReservations(3)).isZero();

        assertThat(reservedCount(eventId)).isZero();
        assertThat(availableCapacity(eventId)).isEqualTo(20);
    }

    @Test
    @DisplayName("a disponibilidade nunca ultrapassa a capacidade total, por mais que se varra")
    void availabilityNeverExceedsTotalCapacity() {
        UUID eventId = createEvent(10);
        UUID firstDue = reserve(eventId, 4);
        UUID secondDue = reserve(eventId, 3);
        UUID stillValid = reserve(eventId, 2);
        assertThat(availableCapacity(eventId)).isEqualTo(1);

        forceDue(firstDue);
        forceDue(secondDue);

        for (int sweep = 0; sweep < 5; sweep++) {
            expirationService.expireDueReservations(BATCH_SIZE);

            // A invariante vale depois de cada passada, e não só no fim: disponibilidade é
            // derivada, e devolver a mais criaria capacidade do nada — a outra face do
            // oversell.
            assertThat(availableCapacity(eventId)).isBetween(0, 10);
            assertThat(reservedCount(eventId)).isBetween(0, 10);
        }

        // Sobram exatamente os assentos da reserva que continua viva.
        assertThat(statusOf(stillValid)).isEqualTo("PENDING");
        assertThat(reservedCount(eventId)).isEqualTo(2);
        assertThat(availableCapacity(eventId)).isEqualTo(8);
    }

    private int inItsOwnTransaction(List<UUID> batch) {
        return transactionTemplate.execute(status -> reservationRepository.markExpired(batch));
    }

    private UUID createEvent(int capacity) {
        return eventService.create(new CreateEventRequest("Evento com prazo", capacity)).id();
    }

    private UUID reserve(UUID eventId, int quantity) {
        return reservationService.create(eventId, new CreateReservationRequest(quantity), null)
                .reservation().id();
    }

    /** Antecipa o vencimento no banco, para não depender do TTL real nem de espera. */
    private void forceDue(UUID reservationId) {
        jdbcTemplate.update(
                "UPDATE reservations SET expires_at = now() - interval '1 minute' WHERE id = ?",
                reservationId);
    }

    private String statusOf(UUID reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM reservations WHERE id = ?", String.class, reservationId);
    }

    private Integer reservedCount(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT reserved_count FROM event_inventory WHERE event_id = ?", Integer.class, eventId);
    }

    private Integer availableCapacity(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT total_capacity - reserved_count FROM event_inventory WHERE event_id = ?",
                Integer.class, eventId);
    }
}
