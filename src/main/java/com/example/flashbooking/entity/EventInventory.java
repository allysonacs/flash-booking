package com.example.flashbooking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Inventário de assentos de um evento — a linha que sofre contenção na flash sale.
 *
 * <p>A associação com {@link Event} existe no banco (chave estrangeira), mas não é mapeada
 * como relacionamento JPA: são agregados carregados de forma independente. Carregar
 * metadados de evento não deve tocar a linha quente, e a venda não deve arrastar o
 * agregado inteiro para a sessão.
 *
 * <p>Nesta fase a entidade apenas modela o estado. O {@code UPDATE} condicional atômico que
 * incrementa {@code reservedCount} sem permitir oversell chega junto com a reserva.
 */
@Entity
@Table(name = "event_inventory")
public class EventInventory {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "total_capacity", nullable = false, updatable = false)
    private int totalCapacity;

    @Column(name = "reserved_count", nullable = false)
    private int reservedCount;

    @UpdateTimestamp(source = SourceType.DB)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EventInventory() {
        // exigido pelo JPA
    }

    public EventInventory(Event event) {
        Objects.requireNonNull(event, "event");
        this.eventId = Objects.requireNonNull(event.getId(), "event.id: o evento precisa estar persistido");
        this.totalCapacity = event.getTotalCapacity();
        this.reservedCount = 0;
    }

    public UUID getEventId() {
        return eventId;
    }

    public int getTotalCapacity() {
        return totalCapacity;
    }

    public int getReservedCount() {
        return reservedCount;
    }

    /** Disponibilidade derivada — nunca persistida, para não haver duas fontes da verdade. */
    public int getAvailableCapacity() {
        return totalCapacity - reservedCount;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EventInventory that)) {
            return false;
        }
        return eventId != null && eventId.equals(that.eventId);
    }

    @Override
    public int hashCode() {
        return EventInventory.class.hashCode();
    }

    @Override
    public String toString() {
        return "EventInventory{eventId=%s, reservedCount=%d/%d}"
                .formatted(eventId, reservedCount, totalCapacity);
    }
}
