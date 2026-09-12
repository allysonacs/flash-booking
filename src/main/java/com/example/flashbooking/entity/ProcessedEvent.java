package com.example.flashbooking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A marca de que uma mensagem já foi processada por um grupo de consumidores.
 *
 * <p>Existe porque a entrega é <strong>at-least-once</strong>, e isso não é um detalhe do
 * Kafka que se possa contornar com configuração: um rebalance, um commit de offset perdido
 * ou uma republicação do relay bastam para a mesma mensagem chegar duas vezes. Tratar
 * "exactly-once" como garantia do broker é justamente o erro que esta tabela evita — a
 * idempotência mora no consumidor, e é o banco que a garante.
 *
 * <p>A chave é o par (mensagem, grupo): grupos diferentes são consumidores diferentes, e
 * cada um tem direito a processar a mensagem uma vez.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @EmbeddedId
    private Key key;

    @Column(name = "processed_at", nullable = false, insertable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
        // exigido pelo JPA
    }

    public ProcessedEvent(UUID messageId, String consumerGroup) {
        this.key = new Key(messageId, consumerGroup);
    }

    public Key getKey() {
        return key;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    /** Identidade composta: a mensagem e quem a consumiu. */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "message_id", nullable = false, updatable = false)
        private UUID messageId;

        @Column(name = "consumer_group", nullable = false, length = 100, updatable = false)
        private String consumerGroup;

        protected Key() {
            // exigido pelo JPA
        }

        public Key(UUID messageId, String consumerGroup) {
            this.messageId = Objects.requireNonNull(messageId, "messageId");
            this.consumerGroup = Objects.requireNonNull(consumerGroup, "consumerGroup");
        }

        public UUID getMessageId() {
            return messageId;
        }

        public String getConsumerGroup() {
            return consumerGroup;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key that)) {
                return false;
            }
            return messageId.equals(that.messageId) && consumerGroup.equals(that.consumerGroup);
        }

        @Override
        public int hashCode() {
            return Objects.hash(messageId, consumerGroup);
        }
    }
}
