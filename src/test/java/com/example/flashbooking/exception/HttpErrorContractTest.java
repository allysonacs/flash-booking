package com.example.flashbooking.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.flashbooking.controller.EventController;
import com.example.flashbooking.service.EventService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Falhas de protocolo — as que o Spring MVC detecta antes de qualquer controller ser
 * chamado — também precisam respeitar o contrato de erros.
 *
 * <p>Esta classe é regressão de um defeito real: o handler genérico de {@code Exception}
 * capturava as exceções do próprio framework e as convertia em 500, de modo que uma rota
 * inexistente respondia "erro interno" e ainda poluía o log com stack trace. Cada teste aqui
 * fixa o status correto que o cliente deve receber.
 */
@WebMvcTest(EventController.class)
class HttpErrorContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventService eventService;

    @Test
    @DisplayName("rota inexistente responde 404, e não 500")
    void unknownRouteReturnsNotFound() throws Exception {
        mockMvc.perform(get("/rota-que-nao-existe"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("método não suportado na rota responde 405")
    void unsupportedMethodReturnsMethodNotAllowed() throws Exception {
        mockMvc.perform(delete("/events/{id}", UUID.randomUUID()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("content-type não suportado responde 415")
    void unsupportedMediaTypeReturnsUnsupportedMediaType() throws Exception {
        mockMvc.perform(post("/events").contentType(MediaType.TEXT_PLAIN).content("nao e json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @DisplayName("toda resposta de erro carrega code, timestamp e instance")
    void everyErrorBodyCarriesTheSameEnvelope() throws Exception {
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/events"))
                .andExpect(jsonPath("$.title").value("Requisição inválida"));
    }
}
