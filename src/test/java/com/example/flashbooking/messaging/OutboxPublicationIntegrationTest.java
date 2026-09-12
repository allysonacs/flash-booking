package com.example.flashbooking.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.example.flashbooking.config.EventProperties;
import com.example.flashbooking.dto.request.CreateEventRequest;
import com.example.flashbooking.dto.request.CreateReservationRequest;
import com.example.flashbooking.exception.InsufficientCapacityException;
import com.example.flashbooking.observability.CorrelationId;
import com.example.flashbooking.service.EventService;
import com.example.flashbooking.service.ReservationService;
import com.example.flashbooking.support.AbstractKafkaIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

/**
 * O lado do produtor: a transação grava o evento, o relay o publica, e nada se perde no
 * caminho.
 *
 * <p>O teste observa os dois lados da fronteira — a linha no PostgreSQL e a mensagem no
 * Kafka — porque é exatamente entre eles que mora o problema que o outbox resolve.
 */
class OutboxPublicationIntegrationTest extends AbstractKafkaIntegrationTest {

    @Autowired
    private EventService eventService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private OutboxRelayService relayService;

    @Autowired
    private EventProperties eventProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private KafkaEventPublisher publisher;

    @Test
    @DisplayName("a transação grava o evento como PENDING e não publica nada antes do commit")
    void reservationWritesTheEventInTheSameTransaction() {
        UUID eventId = createEvent(10);
        UUID reservationId = reserve(eventId, 3);

        Map<String, Object> message = onlyOutboxRow();
        assertThat(message.get("aggregate_id")).hasToString(reservationId.toString());
        assertThat(message.get("event_type")).isEqualTo(ReservationCreated.TYPE);
        assertThat(message.get("status")).isEqualTo("PENDING");
        assertThat(message.get("partition_key")).isEqualTo(eventId.toString());
        assertThat(message.get("published_at")).isNull();

        // O payload é auto-suficiente: quem consome não precisa voltar à API para entender
        // o que aconteceu.
        ReservationCreated event = parse(message);
        assertThat(event.messageId()).isEqualTo(message.get("id"));
        assertThat(event.reservationId()).isEqualTo(reservationId);
        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.quantity()).isEqualTo(3);
    }

    @Test
    @DisplayName("a reserva desfeita não deixa evento para trás")
    void rolledBackReservationLeavesNoEvent() {
        UUID eventId = createEvent(2);

        assertThatThrownBy(() -> reserve(eventId, 5))
                .isInstanceOf(InsufficientCapacityException.class);

        // O rollback levou o evento junto. Este é o caso que a publicação direta no Kafka
        // não tem como desfazer: a mensagem já teria saído, anunciando uma venda que não
        // existe.
        assertThat(outboxCount()).isZero();
    }

    @Test
    @DisplayName("o relay publica no Kafka e marca a mensagem como publicada")
    void relayPublishesAndMarksTheMessage() {
        UUID eventId = createEvent(10);
        UUID reservationId = reserve(eventId, 2);

        int published = relayService.publishPendingBatch(10);

        assertThat(published).isEqualTo(1);
        Map<String, Object> message = onlyOutboxRow();
        assertThat(message.get("status")).isEqualTo("PUBLISHED");
        assertThat(message.get("published_at")).isNotNull();

        ConsumerRecord<String, String> record = readSingleRecordFor(reservationId);
        // A chave é o id do show: reservas do mesmo show caem na mesma partição e mantêm a
        // ordem entre si.
        assertThat(record.key()).isEqualTo(eventId.toString());
        assertThat(record.headers().lastHeader("message-id")).isNotNull();
        assertThat(record.value()).contains(reservationId.toString());
    }

    @Test
    @DisplayName("uma segunda passada do relay não republica o que já foi publicado")
    void relayIsIdempotentAcrossRuns() {
        UUID eventId = createEvent(10);
        reserve(eventId, 1);

        assertThat(relayService.publishPendingBatch(10)).isEqualTo(1);
        assertThat(relayService.publishPendingBatch(10)).isZero();
        assertThat(relayService.publishPendingBatch(10)).isZero();

        assertThat(publishedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("broker fora do ar: a mensagem continua pendente e é publicada na passada seguinte")
    void failedPublicationIsRetriedLater() {
        UUID eventId = createEvent(10);
        UUID reservationId = reserve(eventId, 4);

        doThrow(new EventPublishException("broker indisponível",
                new IllegalStateException("conexão recusada")))
                .when(publisher).publish(any());

        assertThat(relayService.publishPendingBatch(10)).isZero();

        // Nada foi perdido: a mensagem continua na fila, com o registro da tentativa.
        Map<String, Object> afterFailure = onlyOutboxRow();
        assertThat(afterFailure.get("status")).isEqualTo("PENDING");
        assertThat(afterFailure.get("attempts")).isEqualTo(1);
        assertThat(afterFailure.get("last_error")).asString()
                .contains("broker indisponível").contains("conexão recusada");

        doCallRealMethod().when(publisher).publish(any());

        assertThat(relayService.publishPendingBatch(10)).isEqualTo(1);
        assertThat(onlyOutboxRow().get("status")).isEqualTo("PUBLISHED");
        assertThat(readSingleRecordFor(reservationId).value()).contains(reservationId.toString());
    }

    @Test
    @DisplayName("broker fora do ar: o lote para na primeira falha, sem segurar a transação")
    void failingBatchStopsEarlyInsteadOfWaitingOnEveryMessage() {
        UUID eventId = createEvent(10);
        reserve(eventId, 1);
        reserve(eventId, 1);
        reserve(eventId, 1);

        doThrow(new EventPublishException("broker indisponível",
                new IllegalStateException("conexão recusada")))
                .when(publisher).publish(any());

        assertThat(relayService.publishPendingBatch(10)).isZero();

        // Apenas a primeira mensagem gastou uma tentativa; as outras não chegaram a esperar
        // o timeout. Com o Kafka fora, o custo de uma passada é um timeout, não N.
        assertThat(attemptedMessages()).isEqualTo(1);
        assertThat(pendingMessages()).isEqualTo(3);

        doCallRealMethod().when(publisher).publish(any());

        // E nada se perdeu: a passada seguinte publica as três.
        assertThat(relayService.publishPendingBatch(10)).isEqualTo(3);
        assertThat(pendingMessages()).isZero();
    }

    @Test
    @DisplayName("o id de correlação da requisição viaja com o evento até o Kafka")
    void correlationIdTravelsWithTheEvent() {
        CorrelationId.set("venda-rastreada-1");
        UUID reservationId;
        try {
            UUID eventId = createEvent(10);
            reservationId = reserve(eventId, 1);
        } finally {
            CorrelationId.clear();
        }

        // Gravado junto com o evento, na mesma transação da venda.
        assertThat(onlyOutboxRow().get("correlation_id")).isEqualTo("venda-rastreada-1");

        relayService.publishPendingBatch(10);

        // E propagado como header da mensagem: o consumidor, em outra instância, volta a
        // registrar logs sob o id da requisição que originou tudo.
        ConsumerRecord<String, String> record = readSingleRecordFor(reservationId);
        assertThat(new String(record.headers().lastHeader(CorrelationId.KAFKA_HEADER).value(),
                StandardCharsets.UTF_8)).isEqualTo("venda-rastreada-1");
    }

    // ------------------------------------------------------------------ apoio

    private UUID createEvent(int capacity) {
        return eventService.create(new CreateEventRequest("Evento publicado", capacity)).id();
    }

    private UUID reserve(UUID eventId, int quantity) {
        return reservationService.create(eventId, new CreateReservationRequest(quantity), null)
                .reservation().id();
    }

    private Map<String, Object> onlyOutboxRow() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM outbox_messages");
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private Integer outboxCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_messages", Integer.class);
    }

    private Integer attemptedMessages() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_messages WHERE attempts > 0", Integer.class);
    }

    private Integer pendingMessages() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_messages WHERE status = 'PENDING'", Integer.class);
    }

    private Integer publishedCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_messages WHERE status = 'PUBLISHED'", Integer.class);
    }

    private ReservationCreated parse(Map<String, Object> row) {
        return objectMapper.readValue(row.get("payload").toString(), ReservationCreated.class);
    }

    /**
     * Lê o tópico com um consumidor próprio, em um grupo próprio. Grupo diferente significa
     * cópia independente do fluxo: este consumidor não interfere no que a aplicação consome.
     */
    private ConsumerRecord<String, String> readSingleRecordFor(UUID reservationId) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "teste-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {

            consumer.subscribe(List.of(eventProperties.topic().name()));

            long deadline = System.currentTimeMillis() + Duration.ofSeconds(20).toMillis();
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (record.value().contains(reservationId.toString())) {
                        return record;
                    }
                }
            }
            throw new AssertionError("A mensagem da reserva " + reservationId + " não chegou ao tópico");
        }
    }
}
