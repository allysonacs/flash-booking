package com.example.flashbooking.exception;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Cobre a categoria "erro de negócio" do contrato de erros.
 *
 * <p>Ela ainda não tem um caminho HTTP que a produza — a primeira regra desse tipo é o
 * inventário insuficiente, na próxima fase. O teste exercita o handler diretamente para que
 * a categoria já esteja verificada quando esse caminho existir.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("violação de regra de negócio vira 409 preservando o código de erro")
    void domainExceptionBecomesConflict() {
        DomainException exception = new DomainException("SOME_BUSINESS_RULE", "Operação recusada") {
        };

        ProblemDetail problem = handler.handleDomain(exception, request("/events/1/reservations"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getDetail()).isEqualTo("Operação recusada");
        assertThat(problem.getProperties()).containsEntry("code", "SOME_BUSINESS_RULE");
        assertThat(problem.getInstance()).hasToString("/events/1/reservations");
    }

    @Test
    @DisplayName("falha inesperada vira 500 genérico, sem detalhe interno no corpo")
    void unexpectedExceptionIsNotLeaked() {
        ProblemDetail problem = handler.handleUnexpected(
                new IllegalStateException("conexão perdida com o pool xyz"), request("/events"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getDetail()).isEqualTo("Não foi possível processar a requisição");
        assertThat(problem.getDetail()).doesNotContain("conexão perdida");
        assertThat(problem.getProperties()).containsEntry("code", "INTERNAL_ERROR");
    }

    private static HttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return request;
    }
}
