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
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * Compromisso de compra de N ingressos de um evento.
 *
 * <p>A reserva registra <em>quem</em> pediu o quê; a contagem de assentos vive em
 * {@link EventInventory}. A associação com {@link Event} existe como chave estrangeira no
 * banco, mas não é mapeada como relacionamento JPA — criar uma reserva não deve arrastar o
 * agregado do evento para a sessão, e a linha de inventário é atualizada por comando SQL
 * direto, nunca por estado gerenciado.
 *
 * <p>Transições de estado não são feitas por setter: elas acontecem por {@code UPDATE}
 * condicional no repositório, para que duas requisições simultâneas não consigam aplicar a
 * mesma transição duas vezes — e, com isso, devolver o mesmo assento duas vezes.
 */
@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status;

    /**
     * Chave de idempotência fornecida pelo cliente, única por evento. Nula quando o cliente
     * não envia o header — nesse caso cada requisição é uma reserva nova, e o índice
     * parcial no banco deixa essas linhas fora da unicidade.
     */
    @Column(name = "idempotency_key", length = 100, updatable = false)
    private String idempotencyKey;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp(source = SourceType.DB)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Reservation() {
        // exigido pelo JPA
    }

    public Reservation(UUID eventId, int quantity, String idempotencyKey, Instant expiresAt) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity deve ser maior que zero");
        }
        this.quantity = quantity;
        this.idempotencyKey = idempotencyKey;
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.status = ReservationStatus.PENDING;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public int getQuantity() {
        return quantity;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Reservation that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Reservation.class.hashCode();
    }

    @Override
    public String toString() {
        return "Reservation{id=%s, eventId=%s, quantity=%d, status=%s}"
                .formatted(id, eventId, quantity, status);
    }
}
