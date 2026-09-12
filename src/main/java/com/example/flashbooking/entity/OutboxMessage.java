package com.example.flashbooking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.type.SqlTypes;

/**
 * Um evento de integração à espera de publicação.
 *
 * <p>A linha é gravada <strong>dentro da transação do agregado</strong>. É essa
 * co-localização que elimina o dual write: ou a reserva e o evento existem, ou nenhum dos
 * dois existe. Nenhuma coordenação distribuída, nenhuma janela entre o commit e o envio —
 * apenas duas linhas no mesmo commit.
 *
 * <p>O {@code id} da mensagem é também a <strong>chave de idempotência do consumidor</strong>:
 * é ele que vai para {@code processed_events} do outro lado. A mensagem carrega a própria
 * identidade, o que permite reconhecê-la como repetida mesmo depois de atravessar um broker
 * que entrega pelo menos uma vez.
 */
@Entity
@Table(name = "outbox_messages")
public class OutboxMessage {

    /**
     * Identidade da mensagem, atribuída pela aplicação e <strong>não</strong> pelo banco:
     * ela precisa estar dentro do payload para o consumidor deduplicar, e um id gerado no
     * insert só seria conhecido tarde demais.
     */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "partition_key", nullable = false, length = 100, updatable = false)
    private String partitionKey;

    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    /**
     * Id de correlação da requisição que produziu o evento. Viaja com a mensagem até o
     * consumidor, para que o log do efeito possa ser ligado ao log da venda.
     */
    @Column(name = "correlation_id", length = 64, updatable = false)
    private String correlationId;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxMessage() {
        // exigido pelo JPA
    }

    public OutboxMessage(UUID id, String aggregateType, UUID aggregateId, String partitionKey,
                         String eventType, String payload, String correlationId) {
        this.id = Objects.requireNonNull(id, "id");
        this.aggregateType = Objects.requireNonNull(aggregateType, "aggregateType");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        this.partitionKey = Objects.requireNonNull(partitionKey, "partitionKey");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.correlationId = correlationId;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OutboxMessage that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return OutboxMessage.class.hashCode();
    }

    @Override
    public String toString() {
        return "OutboxMessage{id=%s, type=%s, status=%s, attempts=%d}"
                .formatted(id, eventType, status, attempts);
    }
}
