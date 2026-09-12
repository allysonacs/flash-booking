package com.example.flashbooking.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.example.flashbooking.dto.request.CreateEventRequest;
import com.example.flashbooking.dto.request.CreateReservationRequest;
import com.example.flashbooking.service.EventService;
import com.example.flashbooking.service.ReservationService;
import com.example.flashbooking.support.AbstractKafkaIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Falha temporária no consumidor: a mensagem é reentregue e o trabalho acaba acontecendo —
 * uma vez.
 *
 * <p>Este é o cenário que justifica a marca de processamento viver na <strong>mesma
 * transação</strong> que o efeito. A primeira tentativa falha depois de já ter inserido a
 * marca; o rollback a desfaz; a reentrega encontra o terreno limpo e processa de verdade. Se
 * a marca fosse gravada por fora, a mensagem ficaria registrada como processada sem nunca ter
 * sido — e o efeito se perderia em silêncio, que é a pior forma de perder.
 */
class ConsumerFailureRetryIntegrationTest extends AbstractKafkaIntegrationTest {

    @Autowired
    private EventService eventService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private OutboxRelayService relayService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private ReservationEventProcessor processor;

    @Test
    @DisplayName("falha temporária: a mensagem é reentregue e processada exatamente uma vez")
    void temporaryFailureIsRetriedAndProcessedOnce() {
        UUID eventId = eventService.create(new CreateEventRequest("Evento instável", 10)).id();
        UUID reservationId = reservationService
                .create(eventId, new CreateReservationRequest(2), null)
                .reservation().id();

        // A primeira entrega estoura; as seguintes seguem o caminho normal.
        doThrow(new IllegalStateException("indisponibilidade momentânea"))
                .doCallRealMethod()
                .when(processor).process(any());

        relayService.publishPendingBatch(10);

        await(() -> deliveriesFor(reservationId) == 1);
        verify(processor, atLeast(2)).process(any());

        // Reentrega não vira efeito duplicado: uma marca, uma notificação.
        sleepBriefly(Duration.ofSeconds(2));
        assertThat(processedEventRows()).isEqualTo(1);
        assertThat(deliveriesFor(reservationId)).isEqualTo(1);
    }

    @Test
    @DisplayName("falha persistente: a mensagem é descartada sem travar a partição")
    void permanentFailureDoesNotBlockThePartition() {
        UUID eventId = eventService.create(new CreateEventRequest("Evento problemático", 10)).id();
        UUID poisonedReservation = reservationService
                .create(eventId, new CreateReservationRequest(1), null)
                .reservation().id();

        doThrow(new IllegalStateException("defeito persistente")).when(processor).process(any());
        relayService.publishPendingBatch(10);
        await(() -> processorInvocations() >= 3);

        // Esgotadas as tentativas, o offset avança. A mensagem seguinte é processada
        // normalmente — uma mensagem defeituosa não pode parar o consumo do tópico inteiro.
        doCallRealMethod().when(processor).process(any());
        UUID healthyReservation = reservationService
                .create(eventId, new CreateReservationRequest(2), null)
                .reservation().id();
        relayService.publishPendingBatch(10);

        await(() -> deliveriesFor(healthyReservation) == 1);
        assertThat(deliveriesFor(poisonedReservation)).isZero();
    }

    private int processorInvocations() {
        return org.mockito.Mockito.mockingDetails(processor).getInvocations().size();
    }

    private long deliveriesFor(UUID reservationId) {
        return NOTIFICATION_SERVICE.idempotencyKeys().stream()
                .filter(reservationId.toString()::equals)
                .count();
    }

    private Integer processedEventRows() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM processed_events", Integer.class);
    }
}
