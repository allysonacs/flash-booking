package com.example.flashbooking.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Contrato de criação de evento.
 *
 * <p>{@code capacity} é {@link Integer} e não {@code int} de propósito: com o primitivo, um
 * corpo sem o campo chegaria como zero e seria reportado como "deve ser maior que zero",
 * escondendo do cliente que o problema real é um campo ausente.
 */
public record CreateEventRequest(

        @Schema(description = "Nome do evento", example = "Show de Rock", maxLength = 200)
        @NotBlank(message = "name é obrigatório")
        @Size(max = 200, message = "name deve ter no máximo 200 caracteres")
        String name,

        @Schema(description = "Quantidade total de ingressos à venda", example = "300", minimum = "1")
        @NotNull(message = "capacity é obrigatório")
        @Positive(message = "capacity deve ser maior que zero")
        Integer capacity) {
}
