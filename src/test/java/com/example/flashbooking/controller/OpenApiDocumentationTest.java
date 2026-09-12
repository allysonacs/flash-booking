package com.example.flashbooking.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.flashbooking.config.ApiExamples;
import com.example.flashbooking.config.OpenApiConfig;
import com.example.flashbooking.exception.InsufficientCapacityException;
import com.example.flashbooking.service.EventService;
import com.example.flashbooking.service.ReservationService;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springdoc.webmvc.ui.SwaggerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A especificação OpenAPI cobre todos os endpoints dos controllers.
 *
 * <p>Trava contra documentação que envelhece em silêncio: um endpoint novo sem entrada no
 * Swagger, ou uma anotação que deixa de ser lida porque saiu da interface, falha aqui.
 */
@WebMvcTest({EventController.class, ReservationController.class})
@Import(OpenApiConfig.class)
@ImportAutoConfiguration({
        SpringDocConfiguration.class, SpringDocConfigProperties.class, SpringDocWebMvcConfiguration.class,
        SwaggerConfig.class, SwaggerUiConfigProperties.class, SwaggerUiOAuthProperties.class})
class OpenApiDocumentationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventService eventService;

    @MockitoBean
    private ReservationService reservationService;

    @Test
    @DisplayName("todos os endpoints aparecem na especificação, com os status documentados")
    void everyEndpointIsDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Flash Booking API"))
                .andExpect(jsonPath("$.paths['/events'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/events/{id}'].get.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/events/{eventId}/reservations'].post.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/reservations/{id}'].get.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/reservations/{id}/confirmation'].post.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/reservations/{id}'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.components.schemas.Problem.properties.code").exists());
    }

    @Test
    @DisplayName("o header Idempotency-Key é documentado como opcional")
    void idempotencyKeyHeaderIsDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/events/{eventId}/reservations'].post.parameters[?(@.name == 'Idempotency-Key')].in")
                        .value("header"))
                .andExpect(jsonPath("$.paths['/events/{eventId}/reservations'].post.parameters[?(@.name == 'Idempotency-Key')].required")
                        .value(false));
    }

    @Test
    @DisplayName("todo exemplo referenciado por uma anotação existe em components/examples")
    void everyReferencedExampleExists() throws Exception {
        String spec = mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        List<String> references = JsonPath.<List<String>>read(spec, "$..['$ref']").stream()
                .filter(ref -> ref.startsWith("#/components/examples/"))
                .map(ref -> ref.substring("#/components/examples/".length()))
                .toList();
        Map<String, Object> examples = JsonPath.read(spec, "$.components.examples");

        assertThat(references).isNotEmpty();
        assertThat(examples).containsKeys(references.toArray(String[]::new));
    }

    @Test
    @DisplayName("os exemplos são serializados a partir dos objetos, com o código e a mensagem da exceção")
    void examplesAreSerializedFromObjects() throws Exception {
        String example = "$.components.examples." + ApiExamples.INSUFFICIENT_CAPACITY;

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(example + ".value.code").value(InsufficientCapacityException.ERROR_CODE))
                .andExpect(jsonPath(example + ".value.status").value(409))
                .andExpect(jsonPath(example + ".value.detail").isString())
                .andExpect(jsonPath(example + ".value.timestamp").value("2026-09-12T01:07:37.476253Z"))
                .andExpect(jsonPath(example + ".value.errors").doesNotExist());
    }

    @Test
    @DisplayName("o Swagger UI é servido")
    void swaggerUiIsServed() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
