package com.example.flashbooking.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.flashbooking.dto.request.CreateEventRequest;
import com.example.flashbooking.dto.request.CreateReservationRequest;
import com.example.flashbooking.exception.InvalidReservationStateException;
import com.example.flashbooking.service.EventService;
import com.example.flashbooking.service.ReservationExpirationService;
import com.example.flashbooking.service.ReservationService;
import com.example.flashbooking.support.AbstractIntegrationTest;
import com.example.flashbooking.support.ConcurrentRunner;
import com.example.flashbooking.support.ConcurrentRunner.Outcome;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A varredura de expiração sob concorrência — o cenário de várias instâncias da aplicação
 * rodando o mesmo job ao mesmo tempo.
 *
 * <p>Cada thread aqui representa uma instância: todas executam a mesma varredura, contra o
 * mesmo banco, no mesmo instante. O que se verifica é que nenhuma reserva é processada duas
 * vezes e que cada assento volta ao inventário exatamente uma vez — sem eleição de líder e
 * sem nenhum lock fora do PostgreSQL.
 */
class ReservationExpirationConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EventService eventService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationExpirationService expirationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("20 varreduras simultâneas processam cada reserva vencida uma única vez")
    void concurrentWorkersProcessEachReservationOnce() {
        UUID eventId = createEvent(100);
        for (int i = 0; i < 60; i++) {
            expire(reserve(eventId, 1));
        }
        assertThat(reservedCount(eventId)).isEqualTo(60);

        List<Outcome<Integer>> outcomes = ConcurrentRunner.runSimultaneously(20,
                () -> expirationService.expireDueReservations(5));

        assertThat(outcomes).allMatch(Outcome::succeeded);

        // SKIP LOCKED entrega lotes disjuntos: a soma do que os workers processaram é
        // exatamente o número de reservas vencidas, sem nenhuma processada duas vezes.
        int totalProcessed = outcomes.stream().mapToInt(Outcome::value).sum();
        assertThat(totalProcessed).isEqualTo(60);
        assertThat(expiredReservations(eventId)).isEqualTo(60);
        assertThat(reservedCount(eventId)).isZero();
    }

    @Test
    @DisplayName("20 workers disputando a mesma reserva: apenas um a expira")
    void onlyOneWorkerExpiresTheSameReservation() {
        UUID eventId = createEvent(10);
        UUID reservationId = reserve(eventId, 7);
        expire(reservationId);

        List<Outcome<Integer>> outcomes = ConcurrentRunner.runSimultaneously(20,
                () -> expirationService.expireDueReservations(10));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes.stream().mapToInt(Outcome::value).sum()).isEqualTo(1);
        assertThat(statusOf(reservationId)).isEqualTo("EXPIRED");
        assertThat(reservedCount(eventId)).isZero();
    }

    @Test
    @DisplayName("cancelamento e expiração disputando a mesma reserva: um vence, o assento volta uma vez")
    void cancellationAndExpirationCannotBothApply() {
        UUID eventId = createEvent(10);
        UUID reservationId = reserve(eventId, 5);
        expire(reservationId);

        List<Outcome<String>> outcomes = ConcurrentRunner.runSimultaneously(20, () -> {
            if (Thread.currentThread().threadId() % 2 == 0) {
                expirationService.expireDueReservations(10);
                return "expiracao";
            }
            reservationService.cancel(reservationId);
            return "cancelamento";
        });

        // Quem perde a corrida ou não faz nada, ou recebe 409 por estado inválido — nunca
        // um erro inesperado, e nunca uma segunda devolução.
        assertThat(outcomes)
                .filteredOn(outcome -> !outcome.succeeded())
                .allMatch(outcome -> outcome.failure() instanceof InvalidReservationStateException);

        assertThat(statusOf(reservationId)).isIn("CANCELLED", "EXPIRED");
        assertThat(reservedCount(eventId)).isZero();
        assertThat(availableCapacity(eventId)).isEqualTo(10);
    }

    @Test
    @DisplayName("vender, expirar e revender em paralelo: o inventário nunca sai dos limites")
    void inventoryStaysWithinBoundsUnderMixedLoad() {
        UUID eventId = createEvent(50);

        List<Outcome<UUID>> firstWave = ConcurrentRunner.runSimultaneously(50,
                () -> reserve(eventId, 1));
        firstWave.stream().filter(Outcome::succeeded).map(Outcome::value).forEach(this::expire);

        ConcurrentRunner.runSimultaneously(30, () -> {
            if (Thread.currentThread().threadId() % 3 == 0) {
                return expirationService.expireDueReservations(20);
            }
            try {
                reserve(eventId, 1);
            } catch (RuntimeException ignoredContention) {
                // Perder a disputa por um assento é resultado esperado, não falha do teste.
            }
            return 0;
        });

        Integer reserved = reservedCount(eventId);
        assertThat(reserved).isBetween(0, 50);
        assertThat(reserved).isEqualTo(pendingSeats(eventId));
        assertThat(availableCapacity(eventId)).isBetween(0, 50);
    }

    private UUID createEvent(int capacity) {
        return eventService.create(new CreateEventRequest("Evento sob varredura", capacity)).id();
    }

    private UUID reserve(UUID eventId, int quantity) {
        return reservationService.create(eventId, new CreateReservationRequest(quantity), null)
                .reservation().id();
    }

    private void expire(UUID reservationId) {
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

    private Integer expiredReservations(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reservations WHERE event_id = ? AND status = 'EXPIRED'",
                Integer.class, eventId);
    }

    private Integer pendingSeats(UUID eventId) {
        return jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(quantity), 0) FROM reservations
                 WHERE event_id = ? AND status = 'PENDING'
                """, Integer.class, eventId);
    }
}
