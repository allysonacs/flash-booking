package com.example.flashbooking.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Formato das respostas de erro, como descrito na especificação OpenAPI.
 *
 * <p>Não é instanciado: o corpo real é um {@link org.springframework.http.ProblemDetail}
 * montado pelo {@link com.example.flashbooking.exception.GlobalExceptionHandler}. O
 * {@code ProblemDetail} não serve como schema porque guarda as extensões ({@code code},
 * {@code timestamp}, {@code errors}) em um mapa que só é achatado na serialização — documentá-lo
 * diretamente mostraria ao cliente um campo {@code properties} que nunca chega.
 */
@Schema(name = "Problem", description = "Erro no formato RFC 7807 (application/problem+json)")
public record ProblemResponse(

        @Schema(description = "Resumo do tipo de falha", example = "Operação não permitida")
        String title,

        @Schema(description = "Status HTTP", example = "409")
        int status,

        @Schema(description = "Explicação legível desta ocorrência — não programe contra este texto",
                example = "Não há 2 assento(s) disponível(is) para o evento 7f000001-a093-17ce-81a0-9327d71f0000")
        String detail,

        @Schema(description = "Caminho da requisição que falhou",
                example = "/events/7f000001-a093-17ce-81a0-9327d71f0000/reservations")
        String instance,

        @Schema(description = "Código estável da falha, para o cliente programar em cima",
                example = "INSUFFICIENT_CAPACITY",
                allowableValues = {
                        "VALIDATION_ERROR", "MALFORMED_REQUEST", "INVALID_PARAMETER",
                        "EVENT_NOT_FOUND", "RESERVATION_NOT_FOUND", "RESOURCE_NOT_FOUND",
                        "INSUFFICIENT_CAPACITY", "IDEMPOTENCY_KEY_CONFLICT",
                        "INVALID_RESERVATION_STATE", "RESERVATION_EXPIRED",
                        "EVENT_NOT_OPEN_FOR_RESERVATION", "METHOD_NOT_ALLOWED", "NOT_ACCEPTABLE",
                        "UNSUPPORTED_MEDIA_TYPE", "INTERNAL_ERROR"})
        String code,

        @Schema(description = "Momento da falha, em UTC", example = "2026-09-12T01:07:37.476253Z")
        Instant timestamp,

        @Schema(description = "Campos inválidos — presente apenas em VALIDATION_ERROR",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<Violation> errors) {

    @Schema(name = "FieldViolation", description = "Um campo inválido e o motivo")
    public record Violation(

            @Schema(example = "quantity")
            String field,

            @Schema(example = "quantity deve ser maior que zero")
            String message) {
    }
}
