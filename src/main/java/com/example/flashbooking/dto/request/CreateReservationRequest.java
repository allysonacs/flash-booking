package com.example.flashbooking.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Contrato de criação de reserva.
 *
 * <p>Só a quantidade: a identificação do cliente entra quando existir autenticação — um
 * {@code customerId} enviado pelo próprio cliente não identificaria ninguém.
 */
public record CreateReservationRequest(

        @Schema(description = "Quantidade de ingressos a reservar", example = "2", minimum = "1")
        @NotNull(message = "quantity é obrigatório")
        @Positive(message = "quantity deve ser maior que zero")
        Integer quantity) {
}
