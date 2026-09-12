package com.example.flashbooking.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.flashbooking.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Cada índice deste schema existe por causa de uma consulta concreta. Este teste amarra os
 * dois: se alguém remover um índice, o que quebra é este teste — e não o tempo de resposta
 * da flash sale, três meses depois, quando a tabela já não cabe em memória.
 */
class SchemaIndexTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("os índices do caminho crítico e dos jobs estão no schema")
    void criticalIndexesExist() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class);

        assertThat(indexes).contains(
                // Idempotência: consulta por (event_id, idempotency_key) a cada retentativa
                "ux_reservations_event_idempotency_key",
                // Chave estrangeira e consultas por evento — o PostgreSQL não indexa FK sozinho
                "ix_reservations_event_status",
                // A varredura de expiração, executada por todas as instâncias a cada 30s
                "ix_reservations_due",
                // O relay, a cada segundo, em todas as instâncias
                "ix_outbox_messages_pending",
                // As duas passadas de retenção
                "ix_outbox_messages_published",
                "ix_processed_events_processed_at");
    }

    @Test
    @DisplayName("os índices das filas são parciais: indexam a fila viva, não o histórico")
    void queueIndexesArePartial() {
        assertThat(definitionOf("ix_reservations_due")).contains("WHERE").contains("'PENDING'");
        assertThat(definitionOf("ix_outbox_messages_pending")).contains("WHERE").contains("'PENDING'");
        assertThat(definitionOf("ix_outbox_messages_published")).contains("WHERE").contains("'PUBLISHED'");
    }

    private String definitionOf(String indexName) {
        return jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = ?", String.class, indexName);
    }
}
