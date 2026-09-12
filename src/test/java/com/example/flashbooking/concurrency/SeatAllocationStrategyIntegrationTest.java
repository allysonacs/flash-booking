package com.example.flashbooking.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.flashbooking.dto.request.CreateEventRequest;
import com.example.flashbooking.service.EventService;
import com.example.flashbooking.service.allocation.SeatAllocator;
import com.example.flashbooking.support.AbstractIntegrationTest;
import com.example.flashbooking.support.ConcurrentRunner;
import com.example.flashbooking.support.ConcurrentRunner.Outcome;
import com.example.flashbooking.support.NaiveSeatAllocator;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A contraprova: demonstra que é a <em>estratégia de alocação</em> que impede o oversell, e
 * não sorte, nem o banco, nem o volume baixo do teste.
 *
 * <p>O mesmo cenário roda duas vezes, mudando apenas a implementação de
 * {@link SeatAllocator}. Com o {@code UPDATE} condicional, o número de alocações bem
 * sucedidas é exatamente igual ao que o inventário registra. Com a leitura seguida de
 * escrita — a implementação que este projeto recusa — dezenas de requisições "vencem" a
 * mesma vaga e o inventário registra uma fração disso: ingressos vendidos que não existem.
 *
 * <p>Este é o teste para mostrar em code review quando alguém perguntar "mas isso não daria
 * na mesma com um {@code save()}?".
 */
class SeatAllocationStrategyIntegrationTest extends AbstractIntegrationTest {

    private static final int CAPACITY = 10;
    private static final int CONCURRENT_REQUESTS = 20;

    @Autowired
    private SeatAllocator atomicUpdateAllocator;

    @Autowired
    private EventService eventService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("UPDATE condicional: alocações bem sucedidas == assentos reservados == capacidade")
    void atomicUpdateNeverOversells() {
        UUID eventId = createEvent();

        List<Outcome<Boolean>> outcomes = ConcurrentRunner.runSimultaneously(CONCURRENT_REQUESTS,
                () -> transactionTemplate.execute(status -> atomicUpdateAllocator.tryAllocate(eventId, 1)));

        long allocations = countAllocations(outcomes);
        Integer reserved = reservedCount(eventId);

        assertThat(allocations).isEqualTo(CAPACITY);
        assertThat(reserved).isEqualTo(CAPACITY);
        assertThat(allocations).isEqualTo(reserved.longValue());
    }

    @Test
    @DisplayName("ler-decidir-gravar: mais alocações do que assentos — o oversell aparece")
    void readModifyWriteOversells() {
        UUID eventId = createEvent();
        SeatAllocator naiveAllocator = new NaiveSeatAllocator(jdbcTemplate, Duration.ofMillis(200));

        List<Outcome<Boolean>> outcomes = ConcurrentRunner.runSimultaneously(CONCURRENT_REQUESTS,
                () -> naiveAllocator.tryAllocate(eventId, 1));

        long allocations = countAllocations(outcomes);
        Integer reserved = reservedCount(eventId);

        // Cada requisição leu o mesmo contador antes de qualquer uma gravar: todas se
        // julgaram vencedoras. Se cada alocação tivesse virado uma reserva, teríamos
        // vendido mais ingressos do que o evento tem lugares.
        assertThat(allocations).isGreaterThan(CAPACITY);
        assertThat(allocations).isGreaterThan(reserved.longValue());
        assertThat(reserved).isLessThan((int) allocations);
    }

    private UUID createEvent() {
        return eventService.create(new CreateEventRequest("Evento de contraprova", CAPACITY)).id();
    }

    private static long countAllocations(List<Outcome<Boolean>> outcomes) {
        return outcomes.stream()
                .filter(Outcome::succeeded)
                .map(Outcome::value)
                .filter(Boolean.TRUE::equals)
                .count();
    }

    private Integer reservedCount(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT reserved_count FROM event_inventory WHERE event_id = ?", Integer.class, eventId);
    }
}
