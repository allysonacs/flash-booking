package com.example.flashbooking.observability;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.flashbooking.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * O que o Actuator expõe — e, sobretudo, o que ele <strong>não</strong> expõe.
 *
 * <p>Este teste existe como trava: `include: "*"` em um momento de pressa é uma mudança de
 * uma linha, passa em qualquer revisão distraída e publica dump de memória e configuração
 * na porta da aplicação.
 */
@AutoConfigureMockMvc
class ActuatorExposureTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("liveness e readiness respondem separadamente")
    void probesAreSeparate() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("métricas de negócio estão disponíveis para o coletor")
    void businessMetricsAreExposed() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"env", "beans", "configprops", "heapdump", "threaddump",
            "loggers", "shutdown", "mappings"})
    @DisplayName("endpoints administrativos perigosos não são expostos")
    void dangerousEndpointsAreNotExposed(String endpoint) throws Exception {
        mockMvc.perform(get("/actuator/" + endpoint)).andExpect(status().isNotFound());
    }
}
