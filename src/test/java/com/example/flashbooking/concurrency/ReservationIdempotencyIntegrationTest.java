package com.example.flashbooking.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.flashbooking.dto.request.CreateEventRequest;
import com.example.flashbooking.dto.request.CreateReservationRequest;
import com.example.flashbooking.exception.IdempotencyKeyConflictException;
import com.example.flashbooking.service.EventService;
import com.example.flashbooking.service.ReservationCreationResult;
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
 * Idempotência contra um PostgreSQL real, inclusive sob requisições simultâneas.
 *
 * <p>O caso interessante não é a retentativa depois da resposta — é a retentativa que chega
 * <em>antes</em> de a primeira requisição terminar, quando a consulta prévia ainda não tem o
 * que encontrar. É o índice único no banco que resolve essa corrida; nenhum cache em memória
 * resolveria, porque as duas requisições podem estar em instâncias diferentes da aplicação.
 */
class ReservationIdempotencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EventService eventService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("repetir a requisição com a mesma chave devolve a reserva original")
    void repeatedRequestReplaysOriginalReservation() {
        UUID eventId = createEvent(100);
        String key = "pedido-" + UUID.randomUUID();

        ReservationCreationResult first = reserve(eventId, 2, key);
        ReservationCreationResult second = reserve(eventId, 2, key);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.reservation().id()).isEqualTo(first.reservation().id());
        assertThat(reservationCount(eventId)).isEqualTo(1);
        assertThat(reservedCount(eventId)).isEqualTo(2);
    }

    @Test
    @DisplayName("50 requisições simultâneas com a mesma chave criam exatamente uma reserva")
    void concurrentRequestsWithSameKeyCreateExactlyOneReservation() {
        UUID eventId = createEvent(100);
        String key = "pedido-" + UUID.randomUUID();

        List<Outcome<ReservationCreationResult>> outcomes =
                ConcurrentRunner.runSimultaneously(50, () -> reserve(eventId, 2, key));

        assertThat(outcomes).allMatch(Outcome::succeeded);

        List<ReservationCreationResult> results = outcomes.stream().map(Outcome::value).toList();
        assertThat(results.stream().filter(result -> !result.replayed())).hasSize(1);
        assertThat(results.stream().map(result -> result.reservation().id()).distinct()).hasSize(1);

        // O que mais importa: os assentos foram comprometidos uma única vez. As requisições
        // perdedoras tiveram o rollback devolvendo o que haviam tentado reservar.
        assertThat(reservationCount(eventId)).isEqualTo(1);
        assertThat(reservedCount(eventId)).isEqualTo(2);
    }

    @Test
    @DisplayName("chave repetida com quantidade diferente é recusada com conflito")
    void sameKeyWithDifferentPayloadIsRejected() {
        UUID eventId = createEvent(100);
        String key = "pedido-" + UUID.randomUUID();
        reserve(eventId, 2, key);

        assertThatThrownBy(() -> reserve(eventId, 5, key))
                .isInstanceOf(IdempotencyKeyConflictException.class);

        assertThat(reservationCount(eventId)).isEqualTo(1);
        assertThat(reservedCount(eventId)).isEqualTo(2);
    }

    @Test
    @DisplayName("a chave vale por evento: a mesma chave em outro evento é outra reserva")
    void keyIsScopedToTheEvent() {
        UUID firstEvent = createEvent(10);
        UUID secondEvent = createEvent(10);
        String key = "pedido-" + UUID.randomUUID();

        ReservationCreationResult first = reserve(firstEvent, 1, key);
        ReservationCreationResult second = reserve(secondEvent, 1, key);

        assertThat(second.replayed()).isFalse();
        assertThat(second.reservation().id()).isNotEqualTo(first.reservation().id());
        assertThat(reservedCount(firstEvent)).isEqualTo(1);
        assertThat(reservedCount(secondEvent)).isEqualTo(1);
    }

    @Test
    @DisplayName("sem chave, requisições idênticas criam reservas distintas")
    void withoutKeyEachRequestIsANewReservation() {
        UUID eventId = createEvent(10);

        ReservationCreationResult first = reserve(eventId, 1, null);
        ReservationCreationResult second = reserve(eventId, 1, null);

        assertThat(second.reservation().id()).isNotEqualTo(first.reservation().id());
        assertThat(reservationCount(eventId)).isEqualTo(2);
        assertThat(reservedCount(eventId)).isEqualTo(2);
    }

    private ReservationCreationResult reserve(UUID eventId, int quantity, String key) {
        return reservationService.create(eventId, new CreateReservationRequest(quantity), key);
    }

    private UUID createEvent(int capacity) {
        return eventService.create(new CreateEventRequest("Evento idempotente", capacity)).id();
    }

    private Integer reservedCount(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT reserved_count FROM event_inventory WHERE event_id = ?", Integer.class, eventId);
    }

    private Integer reservationCount(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reservations WHERE event_id = ?", Integer.class, eventId);
    }
}
