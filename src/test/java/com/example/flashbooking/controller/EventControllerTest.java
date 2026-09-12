package com.example.flashbooking.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.flashbooking.dto.request.CreateEventRequest;
import com.example.flashbooking.dto.response.EventResponse;
import com.example.flashbooking.entity.EventStatus;
import com.example.flashbooking.exception.EventNotFoundException;
import com.example.flashbooking.service.EventService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Testes da camada HTTP isoladamente: rotas, status, formato do corpo e tradução de erros.
 *
 * <p>O serviço é substituído por um mock porque o que está sob teste aqui é o contrato HTTP
 * — se a resposta de criação é 201, se o {@code Location} aponta para o recurso, se um erro
 * de validação vira 400 com a estrutura combinada.
 */
@WebMvcTest(EventController.class)
class EventControllerTest {

    private static final String VALID_BODY = """
            {"name": "Show de Rock", "capacity": 300}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventService eventService;

    @Test
    @DisplayName("POST /events responde 201 com o recurso e o header Location")
    void createReturnsCreated() throws Exception {
        UUID id = UUID.randomUUID();
        when(eventService.create(any(CreateEventRequest.class))).thenReturn(
                new EventResponse(id, "Show de Rock", EventStatus.ON_SALE, 300, 0, 300, Instant.now()));

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/events/" + id))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Show de Rock"))
                .andExpect(jsonPath("$.status").value("ON_SALE"))
                .andExpect(jsonPath("$.totalCapacity").value(300))
                .andExpect(jsonPath("$.availableCapacity").value(300));
    }

    @Test
    @DisplayName("POST /events com nome em branco responde 400 apontando o campo")
    void createRejectsBlankName() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "  ", "capacity": 10}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));

        verify(eventService, never()).create(any());
    }

    @Test
    @DisplayName("POST /events com capacidade zero responde 400")
    void createRejectsNonPositiveCapacity() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Evento", "capacity": 0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("capacity"))
                .andExpect(jsonPath("$.errors[0].message").value("capacity deve ser maior que zero"));

        verify(eventService, never()).create(any());
    }

    @Test
    @DisplayName("POST /events sem capacidade informa campo ausente, e não 'menor que zero'")
    void createRejectsMissingCapacity() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Evento"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("capacity"))
                .andExpect(jsonPath("$.errors[0].message").value("capacity é obrigatório"));
    }

    @Test
    @DisplayName("POST /events com JSON malformado responde 400 sem vazar detalhe interno")
    void createRejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("O corpo da requisição não pôde ser interpretado"));
    }

    @Test
    @DisplayName("GET /events/{id} responde 200 com a disponibilidade")
    void findByIdReturnsEvent() throws Exception {
        UUID id = UUID.randomUUID();
        when(eventService.findById(id)).thenReturn(
                new EventResponse(id, "Festival", EventStatus.ON_SALE, 100, 40, 60, Instant.now()));

        mockMvc.perform(get("/events/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.reservedCount").value(40))
                .andExpect(jsonPath("$.availableCapacity").value(60));
    }

    @Test
    @DisplayName("GET /events/{id} de evento inexistente responde 404 com código de erro estável")
    void findByIdReturnsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(eventService.findById(id)).thenThrow(new EventNotFoundException(id));

        mockMvc.perform(get("/events/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("Recurso não encontrado"))
                .andExpect(jsonPath("$.instance").value("/events/" + id));
    }

    @Test
    @DisplayName("GET /events/{id} com id fora do formato UUID responde 400, não 500")
    void findByIdRejectsMalformedIdentifier() throws Exception {
        mockMvc.perform(get("/events/{id}", "nao-e-um-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verify(eventService, never()).findById(any());
    }
}
