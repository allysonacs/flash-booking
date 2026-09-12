package com.example.flashbooking.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.flashbooking.dto.request.CreateEventRequest;
import com.example.flashbooking.dto.request.CreateReservationRequest;
import com.example.flashbooking.service.EventService;
import com.example.flashbooking.service.ReservationService;
import com.example.flashbooking.support.AbstractKafkaIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * O lado do consumidor: a mensagem chega, o trabalho acontece — uma vez só, por mais vezes
 * que ela chegue.
 *
 * <p>O caminho testado é o completo: venda → outbox → relay → Kafka → consumidor → serviço
 * externo. O servidor de notificação de mentira é o ponto de observação do fim da linha; se
 * ele recebe duas requisições para a mesma reserva, a idempotência falhou.
 */
class ReservationCreatedConsumerIntegrationTest extends AbstractKafkaIntegrationTest {

    @Autowired
    private EventService eventService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private OutboxRelayService relayService;

    @Autowired
    private ReservationEventProcessor processor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("da venda ao serviço externo: o evento publicado é consumido e processado")
    void publishedEventIsConsumedEndToEnd() {
        UUID eventId = createEvent(10);
        UUID reservationId = reserve(eventId, 2);

        relayService.publishPendingBatch(10);

        await(() -> deliveriesFor(reservationId) == 1);
        assertThat(processedEventRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("mensagem duplicada: o relay republica, o consumidor processa uma vez só")
    void duplicateDeliveryIsProcessedOnce() {
        UUID eventId = createEvent(10);
        UUID reservationId = reserve(eventId, 3);

        relayService.publishPendingBatch(10);
        await(() -> deliveriesFor(reservationId) == 1);

        // Exatamente o que acontece quando o processo morre entre o ack do broker e o commit
        // da marcação: a linha volta a ser pendente e a MESMA mensagem é publicada de novo.
        jdbcTemplate.update(
                "UPDATE outbox_messages SET status = 'PENDING', published_at = NULL WHERE status = 'PUBLISHED'");
        assertThat(relayService.publishPendingBatch(10)).isEqualTo(1);

        // A segunda entrega é reconhecida e descartada: uma marca em processed_events, uma
        // notificação. Idempotência no consumidor é o que transforma at-least-once em
        // efeito único — e é por isso que ela não pode depender de "exactly-once" do broker.
        sleepBriefly(Duration.ofSeconds(2));
        assertThat(deliveriesFor(reservationId)).isEqualTo(1);
        assertThat(processedEventRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("processar a mesma mensagem duas vezes: a segunda não faz nada")
    void processingIsIdempotentAtTheServiceLevel() {
        ReservationCreated event = new ReservationCreated(
                UUID.randomUUID(), Instant.now(), UUID.randomUUID(), UUID.randomUUID(), 2,
                Instant.now().plusSeconds(900));

        assertThat(processor.process(event)).isTrue();
        assertThat(processor.process(event)).isFalse();
        assertThat(processor.process(event)).isFalse();

        assertThat(deliveriesFor(event.reservationId())).isEqualTo(1);
        assertThat(processedEventRows()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ apoio

    private UUID createEvent(int capacity) {
        return eventService.create(new CreateEventRequest("Evento consumido", capacity)).id();
    }

    private UUID reserve(UUID eventId, int quantity) {
        return reservationService.create(eventId, new CreateReservationRequest(quantity), null)
                .reservation().id();
    }

    /** Quantas notificações o serviço externo recebeu para esta reserva. */
    private long deliveriesFor(UUID reservationId) {
        return NOTIFICATION_SERVICE.idempotencyKeys().stream()
                .filter(reservationId.toString()::equals)
                .count();
    }

    private Integer processedEventRows() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_events", Integer.class);
    }
}
