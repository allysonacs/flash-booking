package com.example.flashbooking.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.flashbooking.dto.request.CreateReservationRequest;
import com.example.flashbooking.dto.response.ReservationResponse;
import com.example.flashbooking.entity.ReservationStatus;
import com.example.flashbooking.exception.EventNotFoundException;
import com.example.flashbooking.exception.IdempotencyKeyConflictException;
import com.example.flashbooking.exception.InsufficientCapacityException;
import com.example.flashbooking.exception.InvalidReservationStateException;
import com.example.flashbooking.exception.ReservationExpiredException;
import com.example.flashbooking.exception.ReservationNotFoundException;
import com.example.flashbooking.service.ReservationCreationResult;
import com.example.flashbooking.service.ReservationService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Contrato HTTP das reservas: status, corpo, headers e tradução de erros. */
@WebMvcTest(ReservationController.class)
class ReservationControllerTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final String ONE_TICKET = """
            {"quantity": 1}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationService reservationService;

    @Test
    @DisplayName("POST cria a reserva e responde 201 com Location")
    void createReturnsCreated() throws Exception {
        ReservationResponse reservation = reservationResponse();
        when(reservationService.create(eq(EVENT_ID), any(CreateReservationRequest.class), any()))
                .thenReturn(ReservationCreationResult.created(reservation));

        mockMvc.perform(post("/events/{eventId}/reservations", EVENT_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(ONE_TICKET))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/reservations/" + reservation.id()))
                .andExpect(jsonPath("$.id").value(reservation.id().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.quantity").value(1))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    @DisplayName("retentativa com a mesma chave responde 200, e não 201")
    void replayReturnsOk() throws Exception {
        ReservationResponse reservation = reservationResponse();
        when(reservationService.create(eq(EVENT_ID), any(CreateReservationRequest.class), eq("chave-1")))
                .thenReturn(ReservationCreationResult.replayed(reservation));

        mockMvc.perform(post("/events/{eventId}/reservations", EVENT_ID)
                        .header("Idempotency-Key", "chave-1")
                        .contentType(MediaType.APPLICATION_JSON).content(ONE_TICKET))
                .andExpect(status().isOk())
                .andExpect(header().string("Location", "/reservations/" + reservation.id()))
                .andExpect(jsonPath("$.id").value(reservation.id().toString()));
    }

    @Test
    @DisplayName("quantidade inválida responde 400 sem chegar ao serviço")
    void rejectsInvalidQuantity() throws Exception {
        mockMvc.perform(post("/events/{eventId}/reservations", EVENT_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"quantity": 0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("quantity"));

        verify(reservationService, never()).create(any(), any(), any());
    }

    @Test
    @DisplayName("chave de idempotência acima do limite responde 400")
    void rejectsOversizedIdempotencyKey() throws Exception {
        mockMvc.perform(post("/events/{eventId}/reservations", EVENT_ID)
                        .header("Idempotency-Key", "k".repeat(101))
                        .contentType(MediaType.APPLICATION_JSON).content(ONE_TICKET))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));

        verify(reservationService, never()).create(any(), any(), any());
    }

    @Test
    @DisplayName("evento inexistente responde 404")
    void unknownEventReturnsNotFound() throws Exception {
        when(reservationService.create(eq(EVENT_ID), any(), any()))
                .thenThrow(new EventNotFoundException(EVENT_ID));

        mockMvc.perform(post("/events/{eventId}/reservations", EVENT_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(ONE_TICKET))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("evento esgotado responde 409 com código de negócio")
    void soldOutReturnsConflict() throws Exception {
        when(reservationService.create(eq(EVENT_ID), any(), any()))
                .thenThrow(new InsufficientCapacityException(EVENT_ID, 1));

        mockMvc.perform(post("/events/{eventId}/reservations", EVENT_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(ONE_TICKET))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_CAPACITY"))
                .andExpect(jsonPath("$.title").value("Operação não permitida"));
    }

    @Test
    @DisplayName("chave reutilizada com outro payload responde 409")
    void idempotencyConflictReturnsConflict() throws Exception {
        when(reservationService.create(eq(EVENT_ID), any(), eq("chave-1")))
                .thenThrow(new IdempotencyKeyConflictException("chave-1"));

        mockMvc.perform(post("/events/{eventId}/reservations", EVENT_ID)
                        .header("Idempotency-Key", "chave-1")
                        .contentType(MediaType.APPLICATION_JSON).content(ONE_TICKET))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    @DisplayName("GET devolve a reserva; reserva inexistente responde 404")
    void findByIdReturnsReservationOrNotFound() throws Exception {
        ReservationResponse reservation = reservationResponse();
        when(reservationService.findById(reservation.id())).thenReturn(reservation);

        mockMvc.perform(get("/reservations/{id}", reservation.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(EVENT_ID.toString()));

        UUID unknownId = UUID.randomUUID();
        when(reservationService.findById(unknownId)).thenThrow(new ReservationNotFoundException(unknownId));

        mockMvc.perform(get("/reservations/{id}", unknownId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST da confirmação responde 200 com a reserva confirmada")
    void confirmReturnsConfirmedReservation() throws Exception {
        UUID reservationId = UUID.randomUUID();
        when(reservationService.confirm(reservationId)).thenReturn(confirmedResponse(reservationId));

        mockMvc.perform(post("/reservations/{id}/confirmation", reservationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reservationId.toString()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        verify(reservationService).confirm(reservationId);
    }

    @Test
    @DisplayName("confirmação fora do prazo responde 409 com código próprio")
    void confirmAfterDeadlineReturnsConflict() throws Exception {
        UUID reservationId = UUID.randomUUID();
        when(reservationService.confirm(reservationId))
                .thenThrow(new ReservationExpiredException(reservationId));

        mockMvc.perform(post("/reservations/{id}/confirmation", reservationId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_EXPIRED"));
    }

    @Test
    @DisplayName("confirmar uma reserva cancelada responde 409 de estado inválido")
    void confirmInvalidStateReturnsConflict() throws Exception {
        UUID reservationId = UUID.randomUUID();
        when(reservationService.confirm(reservationId)).thenThrow(
                new InvalidReservationStateException(reservationId, ReservationStatus.CANCELLED, "confirmar"));

        mockMvc.perform(post("/reservations/{id}/confirmation", reservationId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_RESERVATION_STATE"));
    }

    @Test
    @DisplayName("confirmar uma reserva inexistente responde 404")
    void confirmUnknownReservationReturnsNotFound() throws Exception {
        UUID reservationId = UUID.randomUUID();
        when(reservationService.confirm(reservationId))
                .thenThrow(new ReservationNotFoundException(reservationId));

        mockMvc.perform(post("/reservations/{id}/confirmation", reservationId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("DELETE responde 204")
    void cancelReturnsNoContent() throws Exception {
        UUID reservationId = UUID.randomUUID();
        doNothing().when(reservationService).cancel(reservationId);

        mockMvc.perform(delete("/reservations/{id}", reservationId))
                .andExpect(status().isNoContent());

        verify(reservationService).cancel(reservationId);
    }

    @Test
    @DisplayName("DELETE em estado inválido responde 409")
    void cancelInvalidStateReturnsConflict() throws Exception {
        UUID reservationId = UUID.randomUUID();
        doThrow(new InvalidReservationStateException(reservationId, ReservationStatus.EXPIRED, "cancelar"))
                .when(reservationService).cancel(reservationId);

        mockMvc.perform(delete("/reservations/{id}", reservationId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_RESERVATION_STATE"));
    }

    private static ReservationResponse confirmedResponse(UUID reservationId) {
        return new ReservationResponse(reservationId, EVENT_ID, 1, ReservationStatus.CONFIRMED,
                Instant.now().plusSeconds(900), Instant.now());
    }

    private static ReservationResponse reservationResponse() {
        return new ReservationResponse(UUID.randomUUID(), EVENT_ID, 1, ReservationStatus.PENDING,
                Instant.now().plusSeconds(900), Instant.now());
    }
}
