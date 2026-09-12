package com.example.flashbooking.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * Exercita a API de eventos de ponta a ponta: HTTP → controller → service → JPA →
 * PostgreSQL real, e de volta.
 *
 * <p>Depois de cada criação, as linhas são conferidas diretamente no banco por SQL: uma
 * resposta 201 prova apenas que a requisição foi aceita, não que algo foi gravado.
 */
@AutoConfigureMockMvc
class EventApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("cria um evento, grava evento e inventário, e devolve o recurso em Location")
    void createsEventAndPersistsInventory() throws Exception {
        String location = mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Clássico Nacional", "capacity": 45000}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.availableCapacity").value(45000))
                .andExpect(jsonPath("$.reservedCount").value(0))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn()
                .getResponse()
                .getHeader("Location");

        assertThat(location).isNotNull();
        UUID id = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));

        Integer events = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM events WHERE id = ? AND name = ?", Integer.class, id, "Clássico Nacional");
        Integer inventoryCapacity = jdbcTemplate.queryForObject(
                "SELECT total_capacity FROM event_inventory WHERE event_id = ?", Integer.class, id);

        assertThat(events).isEqualTo(1);
        assertThat(inventoryCapacity).isEqualTo(45000);
    }

    @Test
    @DisplayName("consulta um evento recém-criado pelo id devolvido")
    void readsBackCreatedEvent() throws Exception {
        UUID id = createEvent("Peça de Teatro", 80);

        mockMvc.perform(get("/events/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Peça de Teatro"))
                .andExpect(jsonPath("$.status").value("ON_SALE"))
                .andExpect(jsonPath("$.totalCapacity").value(80))
                .andExpect(jsonPath("$.availableCapacity").value(80));
    }

    @Test
    @DisplayName("consulta de evento inexistente responde 404")
    void returnsNotFoundForUnknownEvent() throws Exception {
        mockMvc.perform(get("/events/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("capacidade inválida é recusada com 400 e nada é gravado")
    void rejectsInvalidCapacityWithoutWriting() throws Exception {
        Integer before = countEvents();

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Evento Inválido", "capacity": -5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(countEvents()).isEqualTo(before);
    }

    private UUID createEvent(String name, int capacity) throws Exception {
        String body = mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateEventPayload(name, capacity))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        return UUID.fromString(json.get("id").stringValue());
    }

    private Integer countEvents() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM events", Integer.class);
    }

    private record CreateEventPayload(String name, int capacity) {
    }
}
