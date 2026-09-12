package com.example.flashbooking.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.flashbooking.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * A limpeza que impede as tabelas de mensageria de crescerem para sempre.
 *
 * <p>O que o teste fixa não é só "apaga o velho", e sim <strong>o que ele não apaga</strong>:
 * mensagem ainda pendente nunca sai da fila, e marca de idempotência recente continua lá —
 * apagá-la cedo demais reabriria a duplicata que ela existe para impedir.
 */
@TestPropertySource(properties = {
        "flash-booking.events.retention.published-for=1 hour",
        "flash-booking.events.retention.processed-events-for=2 hours"
})
class MessagingRetentionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MessagingRetentionService retentionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("apaga publicados antigos e preserva pendentes e recentes")
    void purgesOnlyWhatIsSafeToDelete() {
        UUID antigaPublicada = insertOutbox("PUBLISHED", "now() - interval '2 hours'");
        UUID recentePublicada = insertOutbox("PUBLISHED", "now()");
        UUID pendenteAntiga = insertOutbox("PENDING", null);

        insertProcessedEvent("now() - interval '3 hours'");
        UUID marcaRecente = insertProcessedEvent("now()");

        int removed = retentionService.purge();

        assertThat(removed).isEqualTo(2);
        assertThat(outboxExists(antigaPublicada)).isFalse();
        assertThat(outboxExists(recentePublicada)).isTrue();

        // Uma mensagem pendente é trabalho por fazer, por mais antiga que seja: apagá-la
        // seria perder o evento — exatamente o que o outbox existe para impedir.
        assertThat(outboxExists(pendenteAntiga)).isTrue();

        assertThat(processedEventCount()).isEqualTo(1);
        assertThat(processedEventExists(marcaRecente)).isTrue();
    }

    private UUID insertOutbox(String status, String publishedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO outbox_messages
                    (id, aggregate_type, aggregate_id, partition_key, event_type, payload,
                     status, attempts, published_at)
                VALUES (?, 'RESERVATION', ?, ?, 'ReservationCreated', '{}'::jsonb, ?, 1, %s)
                """.formatted(publishedAt == null ? "NULL" : publishedAt),
                id, UUID.randomUUID(), UUID.randomUUID().toString(), status);
        return id;
    }

    private UUID insertProcessedEvent(String processedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO processed_events (message_id, consumer_group, processed_at)
                VALUES (?, 'grupo-de-teste', %s)
                """.formatted(processedAt), id);
        return id;
    }

    private boolean outboxExists(UUID id) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT exists(SELECT 1 FROM outbox_messages WHERE id = ?)", Boolean.class, id));
    }

    private boolean processedEventExists(UUID id) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT exists(SELECT 1 FROM processed_events WHERE message_id = ?)",
                Boolean.class, id));
    }

    private Integer processedEventCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM processed_events", Integer.class);
    }
}
