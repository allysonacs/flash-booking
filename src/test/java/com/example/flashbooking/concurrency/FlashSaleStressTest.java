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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * O cenário de flash sale na forma em que ele é enunciado: <strong>capacidade 100, 1.000
 * requisições concorrentes de 1 ingresso cada</strong>.
 *
 * <p>Este teste não mede desempenho e não afirma nenhum número de vazão — medir latência
 * dentro de uma suíte que sobe containers produziria um número que varia com a máquina e não
 * significa nada. O que ele verifica são as <strong>invariantes</strong>, que precisam valer
 * em qualquer máquina e sob qualquer escalonamento de threads:
 *
 * <pre>
 *   reservas bem-sucedidas   ≤ capacidade total
 *   disponibilidade          ≥ 0
 *   reservas bem-sucedidas + disponibilidade  =  capacidade total
 * </pre>
 *
 * <p>A terceira é a que realmente importa: ela diz que <em>nada se perdeu e nada foi criado</em>.
 * As duas primeiras sozinhas passariam em um sistema que simplesmente recusa tudo.
 *
 * <p>Todas as threads rodam na mesma JVM — mais fraco do que N instâncias apenas na forma.
 * Nenhum estado em memória participa da decisão: cada thread abre sua própria transação e
 * disputa a mesma linha do PostgreSQL, exatamente como processos distintos fariam.
 */
class FlashSaleStressTest extends AbstractIntegrationTest {

    private static final int TOTAL_CAPACITY = 100;
    private static final int CONCURRENT_REQUESTS = 1_000;

    @Autowired
    private EventService eventService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("capacidade 100, 1.000 requisições concorrentes: as invariantes se mantêm")
    void flashSaleKeepsInventoryExact() {
        UUID eventId = eventService.create(
                new CreateEventRequest("Flash sale", TOTAL_CAPACITY)).id();

        List<Outcome<ReservationCreationResult>> outcomes = ConcurrentRunner.runSimultaneously(
                CONCURRENT_REQUESTS,
                () -> reservationService.create(eventId, new CreateReservationRequest(1), null));

        long successful = outcomes.stream().filter(Outcome::succeeded).count();
        int available = availableCapacity(eventId);

        assertThat(successful).isLessThanOrEqualTo(TOTAL_CAPACITY);
        assertThat(available).isGreaterThanOrEqualTo(0);
        assertThat(successful + available).isEqualTo(TOTAL_CAPACITY);

        // Com demanda dez vezes maior que a oferta, o evento esgota: o sistema não só evita
        // o oversell como também não deixa de vender o que tinha.
        assertThat(successful).isEqualTo(TOTAL_CAPACITY);
        assertThat(available).isZero();

        // Quem não vendeu recebeu a recusa correta — esgotado é resposta de negócio, não
        // defeito, e não pode se confundir com erro inesperado.
        assertThat(outcomes.stream().filter(outcome -> !outcome.succeeded()).toList())
                .hasSize(CONCURRENT_REQUESTS - TOTAL_CAPACITY)
                .allMatch(outcome -> outcome.failure() instanceof InsufficientCapacityException);

        // E o inventário espelha exatamente as reservas gravadas: o contador não é uma
        // verdade paralela.
        assertThat(reservedCount(eventId)).isEqualTo(TOTAL_CAPACITY);
        assertThat(pendingSeats(eventId)).isEqualTo(TOTAL_CAPACITY);
        assertThat(reservationRows(eventId)).isEqualTo(TOTAL_CAPACITY);

        // Cada venda deixou seu evento de integração na mesma transação — nem um a mais
        // (venda fantasma), nem um a menos (venda sem rastro).
        assertThat(outboxRows(eventId)).isEqualTo(TOTAL_CAPACITY);
    }

    private Integer availableCapacity(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT total_capacity - reserved_count FROM event_inventory WHERE event_id = ?",
                Integer.class, eventId);
    }

    private Integer reservedCount(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT reserved_count FROM event_inventory WHERE event_id = ?", Integer.class, eventId);
    }

    private Integer pendingSeats(UUID eventId) {
        return jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(quantity), 0) FROM reservations
                 WHERE event_id = ? AND status = 'PENDING'
                """, Integer.class, eventId);
    }

    private Integer reservationRows(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reservations WHERE event_id = ?", Integer.class, eventId);
    }

    private Integer outboxRows(UUID eventId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM outbox_messages
                 WHERE partition_key = ? AND event_type = 'ReservationCreated'
                """, Integer.class, eventId.toString());
    }
}
