package com.example.flashbooking.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.flashbooking.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * O contrato de rastreabilidade: toda resposta carrega um id de correlação, e um id que veio
 * de fora é preservado.
 *
 * <p>O segundo caso é o que faz o rastro atravessar sistemas: se cada salto gerasse um id
 * novo, não haveria como ligar o log do gateway ao log desta aplicação.
 */
@AutoConfigureMockMvc
class CorrelationIdFilterTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("sem header de entrada, a resposta traz um id gerado")
    void generatesCorrelationIdWhenAbsent() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationId.HEADER))
                .andReturn();

        assertThat(result.getResponse().getHeader(CorrelationId.HEADER)).isNotBlank();
    }

    @Test
    @DisplayName("o id enviado pelo cliente é preservado na resposta")
    void keepsIncomingCorrelationId() throws Exception {
        mockMvc.perform(get("/actuator/health").header(CorrelationId.HEADER, "pedido-42"))
                .andExpect(header().string(CorrelationId.HEADER, "pedido-42"));
    }

    @Test
    @DisplayName("X-Request-Id também é aceito, porque é o nome que muitos proxies usam")
    void acceptsRequestIdAlias() throws Exception {
        mockMvc.perform(get("/actuator/health").header(CorrelationId.REQUEST_ID_HEADER, "proxy-7"))
                .andExpect(header().string(CorrelationId.HEADER, "proxy-7"));
    }

    @Test
    @DisplayName("id malformado é higienizado antes de entrar no log")
    void sanitizesHostileInput() throws Exception {
        // Um "id" com quebra de linha injetaria linhas falsas no arquivo de log; um id
        // gigante encheria todo evento registrado a partir dele.
        MvcResult result = mockMvc.perform(get("/actuator/health")
                        .header(CorrelationId.HEADER, "abc\ndef ERROR fake-entry"))
                .andReturn();

        String returned = result.getResponse().getHeader(CorrelationId.HEADER);
        assertThat(returned).doesNotContain("\n").doesNotContain(" ").hasSizeLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("o MDC não vaza para a requisição seguinte")
    void clearsContextAfterEachRequest() throws Exception {
        mockMvc.perform(get("/actuator/health").header(CorrelationId.HEADER, "primeiro"));

        // Se o filtro não limpasse o MDC, a thread carregaria "primeiro" adiante.
        assertThat(CorrelationId.current()).isNull();
    }
}
