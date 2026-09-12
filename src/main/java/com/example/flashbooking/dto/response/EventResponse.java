package com.example.flashbooking.dto.response;

import com.example.flashbooking.entity.Event;
import com.example.flashbooking.entity.EventInventory;
import com.example.flashbooking.entity.EventStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Representação pública de um evento e de sua disponibilidade.
 *
 * <p>A conversão vive aqui, como fábrica estática, em vez de em uma classe {@code Mapper}
 * dedicada: o mapeamento é trivial e sem ramificação, e uma classe intermediária só
 * adicionaria um salto a mais para quem lê o código.
 *
 * <p>{@code availableCapacity} é calculado a cada leitura, nunca lido de uma coluna — ver
 * ADR-0008.
 */
public record EventResponse(
        @Schema(example = "7f000001-a093-12b9-81a0-9312c1270000")
        UUID id,
        @Schema(example = "Show de Rock")
        String name,
        @Schema(description = "Estado comercial do evento; só `ON_SALE` aceita reservas", example = "ON_SALE")
        EventStatus status,
        @Schema(description = "Capacidade total do evento", example = "300")
        int totalCapacity,
        @Schema(description = "Assentos ocupados por reservas `PENDING` e `CONFIRMED`", example = "2")
        int reservedCount,
        @Schema(description = "Assentos ainda disponíveis (`totalCapacity - reservedCount`)", example = "298")
        int availableCapacity,
        @Schema(example = "2026-09-12T00:44:34.998217Z")
        Instant createdAt) {

    public static EventResponse from(Event event, EventInventory inventory) {
        return new EventResponse(
                event.getId(),
                event.getName(),
                event.getStatus(),
                event.getTotalCapacity(),
                inventory.getReservedCount(),
                inventory.getAvailableCapacity(),
                event.getCreatedAt());
    }
}
