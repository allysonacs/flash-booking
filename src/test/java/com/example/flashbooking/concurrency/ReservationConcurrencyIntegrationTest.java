package com.example.flashbooking.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.flashbooking.dto.request.CreateEventRequest;
import com.example.flashbooking.dto.request.CreateReservationRequest;
import com.example.flashbooking.exception.InsufficientCapacityException;
import com.example.flashbooking.service.EventService;
import com.example.flashbooking.service.ReservationCreationResult;
import com.example.flashbooking.service.ReservationService;
import com.example.flashbooking.support.AbstractIntegrationTest;
import com.example.flashbooking.support.ConcurrentRunner;
import com.example.flashbooking.support.ConcurrentRunner.Outcome;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * O teste que define o projeto.
 *
 * <p>Cada cenário dispara dezenas ou centenas de reservas <strong>ao mesmo tempo</strong>
 * contra um PostgreSQL real e confere a invariante que o sistema não pode perder: o número
 * de ingressos vendidos nunca ultrapassa a capacidade, e o contador de assentos nunca fica
 * negativo nem diverge das reservas efetivamente gravadas.
 *
 * <p>As threads rodam todas na mesma JVM, o que é um teste mais fraco do que N instâncias da
 * API — mas apenas na forma. A coordenação testada aqui não acontece em memória: ela
 * acontece no PostgreSQL, em uma transação por reserva, exatamente como aconteceria entre
 * processos diferentes. Nenhum {@code synchronized}, nenhum lock de JVM, nenhum estado
 * compartilhado participa do resultado.
 */
class ReservationConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EventService eventService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("100 reservas simultâneas para 100 lugares: todas passam e o inventário fecha em 100")
    void sellsExactlyTheCapacityWhenDemandMatchesIt() {
        UUID eventId = createEvent(100);

        List<Outcome<ReservationCreationResult>> outcomes =
                ConcurrentRunner.runSimultaneously(100, () -> reserve(eventId, 1));

        assertThat(successes(outcomes)).isEqualTo(100);
        assertThat(reservedCount(eventId)).isEqualTo(100);
        assertThat(pendingReservedSeats(eventId)).isEqualTo(100);
    }

    @Test
    @DisplayName("200 reservas simultâneas para 100 lugares: exatamente 100 passam e 100 falham")
    void neverOversellsWhenDemandExceedsCapacity() {
        UUID eventId = createEvent(100);

        List<Outcome<ReservationCreationResult>> outcomes =
                ConcurrentRunner.runSimultaneously(200, () -> reserve(eventId, 1));

        assertThat(successes(outcomes)).isEqualTo(100);
        assertThat(reservedCount(eventId)).isEqualTo(100);
        assertThat(pendingReservedSeats(eventId)).isEqualTo(100);

        // As 100 que falharam falharam pelo motivo certo: disputa perdida, e não defeito.
        List<Throwable> failures = outcomes.stream()
                .filter(outcome -> !outcome.succeeded())
                .map(Outcome::failure)
                .toList();
        assertThat(failures).hasSize(100).allMatch(InsufficientCapacityException.class::isInstance);
    }

    @Test
    @DisplayName("1000 reservas simultâneas para 100 lugares: o inventário continua exato")
    void holdsUnderHeavyContention() {
        UUID eventId = createEvent(100);

        List<Outcome<ReservationCreationResult>> outcomes =
                ConcurrentRunner.runSimultaneously(1000, () -> reserve(eventId, 1));

        assertThat(successes(outcomes)).isEqualTo(100);
        assertThat(reservedCount(eventId)).isEqualTo(100);
        assertThat(pendingReservedSeats(eventId)).isEqualTo(100);
    }

    @Test
    @DisplayName("reservas de 3 lugares em 100 lugares: para no último pedido que cabe inteiro")
    void doesNotSellPartialQuantities() {
        UUID eventId = createEvent(100);

        List<Outcome<ReservationCreationResult>> outcomes =
                ConcurrentRunner.runSimultaneously(100, () -> reserve(eventId, 3));

        // 33 reservas de 3 = 99 lugares; a 34ª precisaria de 102 e é recusada inteira.
        assertThat(successes(outcomes)).isEqualTo(33);
        assertThat(reservedCount(eventId)).isEqualTo(99);
        assertThat(pendingReservedSeats(eventId)).isEqualTo(99);
    }

    @Test
    @DisplayName("cancelamentos simultâneos da mesma reserva devolvem o assento uma única vez")
    void concurrentCancellationsReleaseSeatsOnlyOnce() {
        UUID eventId = createEvent(50);
        UUID reservationId = reserve(eventId, 10).reservation().id();
        assertThat(reservedCount(eventId)).isEqualTo(10);

        List<Outcome<Void>> outcomes = ConcurrentRunner.runSimultaneously(20, () -> {
            reservationService.cancel(reservationId);
            return null;
        });

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(reservedCount(eventId)).isZero();
        assertThat(pendingReservedSeats(eventId)).isZero();
    }

    @Test
    @DisplayName("cancelamentos simultâneos de reservas distintas devolvem exatamente o que foi cancelado")
    void inventoryAlwaysMatchesPendingReservations() {
        UUID eventId = createEvent(60);

        Queue<UUID> toCancel = ConcurrentRunner.runSimultaneously(60,
                        () -> reserve(eventId, 1).reservation().id())
                .stream()
                .filter(Outcome::succeeded)
                .map(Outcome::value)
                .limit(25)
                .collect(Collectors.toCollection(ConcurrentLinkedQueue::new));

        assertThat(reservedCount(eventId)).isEqualTo(60);

        ConcurrentRunner.runSimultaneously(toCancel.size(), () -> {
            reservationService.cancel(toCancel.poll());
            return null;
        });

        assertThat(reservedCount(eventId)).isEqualTo(35);
        assertThat(pendingReservedSeats(eventId)).isEqualTo(35);
    }

    private ReservationCreationResult reserve(UUID eventId, int quantity) {
        return reservationService.create(eventId, new CreateReservationRequest(quantity), null);
    }

    private UUID createEvent(int capacity) {
        return eventService.create(new CreateEventRequest("Evento de carga", capacity)).id();
    }

    private static long successes(List<? extends Outcome<?>> outcomes) {
        return outcomes.stream().filter(Outcome::succeeded).count();
    }

    private Integer reservedCount(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT reserved_count FROM event_inventory WHERE event_id = ?", Integer.class, eventId);
    }

    /** Soma dos assentos comprometidos por reservas vivas — deve espelhar o inventário. */
    private Integer pendingReservedSeats(UUID eventId) {
        return jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(quantity), 0) FROM reservations
                 WHERE event_id = ? AND status = 'PENDING'
                """, Integer.class, eventId);
    }
}
