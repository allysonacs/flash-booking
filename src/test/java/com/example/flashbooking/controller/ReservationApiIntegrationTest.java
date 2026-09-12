package com.example.flashbooking.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.flashbooking.dto.request.CreateEventRequest;
import com.example.flashbooking.service.EventService;
import com.example.flashbooking.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Ciclo de vida completo da reserva pela API, contra um PostgreSQL real: reservar, consultar,
 * confirmar, cancelar, e o efeito de cada passo na disponibilidade do evento.
 *
 * <p>O efeito de cada transição no inventário é a parte que importa aqui, e ela não é
 * uniforme: cancelar e expirar devolvem os assentos, confirmar não — porque os assentos já
 * foram descontados na criação.
 */
@AutoConfigureMockMvc
class ReservationApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventService eventService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("reservar reduz a disponibilidade; cancelar devolve exatamente o que foi reservado")
    void reservationLifecycleAdjustsAvailability() throws Exception {
        UUID eventId = createEvent(10);

        String body = mockMvc.perform(reservationRequest(eventId, 3))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.quantity").value(3))
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        UUID reservationId = idOf(body);
        assertAvailability(eventId, 7);

        mockMvc.perform(get("/reservations/{id}", reservationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reservationId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(delete("/reservations/{id}", reservationId))
                .andExpect(status().isNoContent());

        assertAvailability(eventId, 10);
        mockMvc.perform(get("/reservations/{id}", reservationId))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("repetir o DELETE responde 204 e não devolve o assento duas vezes")
    void cancellationIsIdempotent() throws Exception {
        UUID eventId = createEvent(10);
        UUID reservationId = idOf(mockMvc.perform(reservationRequest(eventId, 4))
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(delete("/reservations/{id}", reservationId)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/reservations/{id}", reservationId)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/reservations/{id}", reservationId)).andExpect(status().isNoContent());

        assertAvailability(eventId, 10);
        assertThat(reservedCount(eventId)).isZero();
    }

    @Test
    @DisplayName("confirmar não muda a disponibilidade: os assentos já estavam comprometidos")
    void confirmationKeepsSeatsCommitted() throws Exception {
        UUID eventId = createEvent(10);
        UUID reservationId = idOf(mockMvc.perform(reservationRequest(eventId, 3))
                .andReturn().getResponse().getContentAsString());
        assertAvailability(eventId, 7);

        mockMvc.perform(post("/reservations/{id}/confirmation", reservationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reservationId.toString()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // A criação já havia descontado os 3 assentos; confirmar apenas impede que voltem.
        assertAvailability(eventId, 7);
        assertThat(reservedCount(eventId)).isEqualTo(3);

        mockMvc.perform(get("/reservations/{id}", reservationId))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("repetir a confirmação responde 200 e não conta a venda duas vezes")
    void confirmationIsIdempotent() throws Exception {
        UUID eventId = createEvent(10);
        UUID reservationId = idOf(mockMvc.perform(reservationRequest(eventId, 2))
                .andReturn().getResponse().getContentAsString());

        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(post("/reservations/{id}/confirmation", reservationId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CONFIRMED"));
        }

        assertAvailability(eventId, 8);
        assertThat(reservedCount(eventId)).isEqualTo(2);
    }

    @Test
    @DisplayName("uma reserva confirmada não pode mais ser cancelada, e o assento não volta")
    void confirmedReservationCannotBeCancelled() throws Exception {
        UUID eventId = createEvent(10);
        UUID reservationId = idOf(mockMvc.perform(reservationRequest(eventId, 4))
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(post("/reservations/{id}/confirmation", reservationId))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/reservations/{id}", reservationId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_RESERVATION_STATE"));

        assertAvailability(eventId, 6);
        assertThat(reservedCount(eventId)).isEqualTo(4);
    }

    @Test
    @DisplayName("confirmar depois do prazo responde 409 e mantém a reserva com a varredura")
    void confirmationAfterDeadlineReturnsConflict() throws Exception {
        UUID eventId = createEvent(10);
        UUID reservationId = idOf(mockMvc.perform(reservationRequest(eventId, 3))
                .andReturn().getResponse().getContentAsString());

        jdbcTemplate.update(
                "UPDATE reservations SET expires_at = now() - interval '1 minute' WHERE id = ?",
                reservationId);

        mockMvc.perform(post("/reservations/{id}/confirmation", reservationId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_EXPIRED"));

        // A reserva continua PENDING: quem a encerra é a varredura, não a confirmação
        // recusada — e os assentos continuam presos até lá, como antes da tentativa.
        mockMvc.perform(get("/reservations/{id}", reservationId))
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertAvailability(eventId, 7);
    }

    @Test
    @DisplayName("confirmar reserva inexistente responde 404")
    void confirmationOfUnknownReservationReturnsNotFound() throws Exception {
        mockMvc.perform(post("/reservations/{id}/confirmation", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("pedir mais do que existe responde 409 e não grava reserva nenhuma")
    void rejectsReservationBeyondCapacity() throws Exception {
        UUID eventId = createEvent(5);

        mockMvc.perform(reservationRequest(eventId, 6))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_CAPACITY"));

        assertAvailability(eventId, 5);
        assertThat(reservationCount(eventId)).isZero();
    }

    @Test
    @DisplayName("esgotar o evento: a última reserva que cabe passa, a seguinte é recusada")
    void allowsExactlyTheRemainingSeats() throws Exception {
        UUID eventId = createEvent(2);

        mockMvc.perform(reservationRequest(eventId, 2)).andExpect(status().isCreated());
        mockMvc.perform(reservationRequest(eventId, 1)).andExpect(status().isConflict());

        assertAvailability(eventId, 0);
        assertThat(reservedCount(eventId)).isEqualTo(2);
    }

    @Test
    @DisplayName("reservar em evento inexistente responde 404")
    void rejectsReservationForUnknownEvent() throws Exception {
        mockMvc.perform(reservationRequest(UUID.randomUUID(), 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("reservar em evento cancelado responde 409")
    void rejectsReservationForClosedEvent() throws Exception {
        UUID eventId = createEvent(10);
        jdbcTemplate.update("UPDATE events SET status = 'CANCELLED' WHERE id = ?", eventId);

        mockMvc.perform(reservationRequest(eventId, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_OPEN_FOR_RESERVATION"));

        assertThat(reservedCount(eventId)).isZero();
    }

    @Test
    @DisplayName("consultar reserva inexistente responde 404")
    void unknownReservationReturnsNotFound() throws Exception {
        mockMvc.perform(get("/reservations/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
    }

    private org.springframework.test.web.servlet.RequestBuilder reservationRequest(UUID eventId, int quantity) {
        return post("/events/{eventId}/reservations", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\": %d}".formatted(quantity));
    }

    private UUID createEvent(int capacity) {
        return eventService.create(new CreateEventRequest("Evento de integração", capacity)).id();
    }

    private void assertAvailability(UUID eventId, int expected) throws Exception {
        mockMvc.perform(get("/events/{id}", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableCapacity").value(expected));
    }

    private UUID idOf(String responseBody) {
        JsonNode json = objectMapper.readTree(responseBody);
        return UUID.fromString(json.get("id").stringValue());
    }

    private Integer reservedCount(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT reserved_count FROM event_inventory WHERE event_id = ?", Integer.class, eventId);
    }

    private Integer reservationCount(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reservations WHERE event_id = ?", Integer.class, eventId);
    }
}
