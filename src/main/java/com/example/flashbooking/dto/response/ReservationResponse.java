package com.example.flashbooking.dto.response;

import com.example.flashbooking.entity.Reservation;
import com.example.flashbooking.entity.ReservationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** Representação pública de uma reserva. */
public record ReservationResponse(
        @Schema(example = "7f000001-a093-17ce-81a0-9327d8580001")
        UUID id,
        @Schema(example = "7f000001-a093-17ce-81a0-9327d71f0000")
        UUID eventId,
        @Schema(example = "2")
        int quantity,
        @Schema(description = "`PENDING` → `CONFIRMED` | `CANCELLED` | `EXPIRED`", example = "PENDING")
        ReservationStatus status,
        @Schema(description = "Prazo para confirmar; depois dele a reserva expira e devolve os assentos",
                example = "2026-09-12T01:22:37.176565Z")
        Instant expiresAt,
        @Schema(example = "2026-09-12T01:07:37.173602Z")
        Instant createdAt) {

    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getEventId(),
                reservation.getQuantity(),
                reservation.getStatus(),
                reservation.getExpiresAt(),
                reservation.getCreatedAt());
    }
}
