package com.example.flashbooking.controller;

import com.example.flashbooking.config.ApiExamples;
import com.example.flashbooking.config.OpenApiConfig;
import com.example.flashbooking.dto.request.CreateReservationRequest;
import com.example.flashbooking.dto.response.ProblemResponse;
import com.example.flashbooking.dto.response.ReservationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

/**
 * Contrato documentado da API de reservas.
 *
 * <p>Documentação OpenAPI e restrições de validação da entrada. Mapeamento de rota e
 * comportamento ficam no {@link ReservationController}, que implementa esta interface. Os
 * exemplos de erro são objetos construídos em {@link ApiExamples} e referenciados aqui pelo nome.
 *
 * <p>As restrições precisam estar aqui, e não no controller: o Bean Validation recusa
 * (HV000151) um método sobrescrito que redefine as restrições de parâmetro do contrato.
 */
@Tag(name = OpenApiConfig.RESERVATIONS_TAG)
public interface ReservationApi {

    String RESERVATION_ID_EXAMPLE = "7f000001-a093-17ce-81a0-9327d8580001";

    @Operation(
            summary = "Reserva ingressos de um evento",
            description = """
                    Compromete os assentos atomicamente — a reserva só é criada se houver \
                    capacidade, mesmo sob milhares de requisições simultâneas em várias instâncias. \
                    A reserva nasce `PENDING` e, sem confirmação até `expiresAt`, expira e devolve \
                    os assentos.

                    **Idempotência:** com o header `Idempotency-Key`, repetir a requisição devolve \
                    a reserva original com `200 OK` (e não `201`, porque nada foi criado desta \
                    vez). Reusar a chave com outro payload responde `409 IDEMPOTENCY_KEY_CONFLICT`.""")
    @ApiResponse(responseCode = "201", description = "Reserva criada",
            headers = @Header(name = "Location", description = "URL da reserva criada",
                    schema = @Schema(type = "string", example = "/reservations/" + RESERVATION_ID_EXAMPLE)))
    @ApiResponse(responseCode = "200", description = "Retentativa com a mesma `Idempotency-Key`: devolve a reserva original",
            headers = @Header(name = "Location", description = "URL da reserva original",
                    schema = @Schema(type = "string", example = "/reservations/" + RESERVATION_ID_EXAMPLE)))
    @ApiResponse(responseCode = "400", description = "Corpo inválido, `Idempotency-Key` longa demais ou id fora do formato UUID",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemResponse.class),
                    examples = @ExampleObject(name = "VALIDATION_ERROR", ref = ApiExamples.RESERVATION_VALIDATION_ERROR)))
    @ApiResponse(responseCode = "404", description = "Evento inexistente",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemResponse.class),
                    examples = @ExampleObject(name = "EVENT_NOT_FOUND", ref = ApiExamples.RESERVATION_EVENT_NOT_FOUND)))
    @ApiResponse(responseCode = "409", description = "Sem assentos suficientes, evento fechado para venda ou chave de idempotência reusada",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemResponse.class),
                    examples = {
                            @ExampleObject(name = "INSUFFICIENT_CAPACITY", ref = ApiExamples.INSUFFICIENT_CAPACITY),
                            @ExampleObject(name = "EVENT_NOT_OPEN_FOR_RESERVATION", ref = ApiExamples.EVENT_NOT_OPEN_FOR_RESERVATION),
                            @ExampleObject(name = "IDEMPOTENCY_KEY_CONFLICT", ref = ApiExamples.IDEMPOTENCY_KEY_CONFLICT)
                    }))
    ResponseEntity<ReservationResponse> create(
            @Parameter(description = "Identificador do evento", example = "7f000001-a093-17ce-81a0-9327d71f0000")
            UUID eventId,
            @Valid CreateReservationRequest request,
            @Parameter(description = "Chave opcional, até 100 caracteres, que torna a retentativa segura",
                    example = "pedido-42")
            @Size(max = 100, message = "Idempotency-Key deve ter no máximo 100 caracteres")
            String idempotencyKey);

    @Operation(summary = "Consulta uma reserva")
    @ApiResponse(responseCode = "200", description = "Reserva encontrada")
    @ApiResponse(responseCode = "400", description = "Identificador fora do formato UUID",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemResponse.class),
                    examples = @ExampleObject(name = "INVALID_PARAMETER", ref = ApiExamples.RESERVATION_INVALID_PARAMETER)))
    @ApiResponse(responseCode = "404", description = "Reserva inexistente",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemResponse.class),
                    examples = @ExampleObject(name = "RESERVATION_NOT_FOUND", ref = ApiExamples.RESERVATION_NOT_FOUND)))
    ReservationResponse findById(
            @Parameter(description = "Identificador da reserva", example = RESERVATION_ID_EXAMPLE)
            UUID id);

    @Operation(
            summary = "Confirma uma reserva",
            description = """
                    Os ingressos passam a ser do cliente e deixam de ter prazo. Não altera a \
                    disponibilidade do evento: os assentos já foram comprometidos na criação.

                    Idempotente: confirmar de novo uma reserva já `CONFIRMED` responde `200` com a \
                    mesma reserva. Só é aceita enquanto o prazo (`expiresAt`) não venceu.""")
    @ApiResponse(responseCode = "200", description = "Reserva confirmada (ou já estava confirmada)")
    @ApiResponse(responseCode = "400", description = "Identificador fora do formato UUID",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemResponse.class),
                    examples = @ExampleObject(name = "INVALID_PARAMETER", ref = ApiExamples.RESERVATION_INVALID_PARAMETER)))
    @ApiResponse(responseCode = "404", description = "Reserva inexistente",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemResponse.class),
                    examples = @ExampleObject(name = "RESERVATION_NOT_FOUND", ref = ApiExamples.RESERVATION_NOT_FOUND)))
    @ApiResponse(responseCode = "409", description = "Prazo vencido ou reserva já cancelada/expirada",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemResponse.class),
                    examples = {
                            @ExampleObject(name = "RESERVATION_EXPIRED", ref = ApiExamples.RESERVATION_EXPIRED),
                            @ExampleObject(name = "INVALID_RESERVATION_STATE", ref = ApiExamples.CONFIRM_INVALID_RESERVATION_STATE)
                    }))
    ReservationResponse confirm(
            @Parameter(description = "Identificador da reserva", example = RESERVATION_ID_EXAMPLE)
            UUID id);

    @Operation(
            summary = "Cancela uma reserva e devolve os assentos",
            description = """
                    Os assentos voltam para a disponibilidade do evento na mesma transação que \
                    muda o estado da reserva. Idempotente: cancelar de novo responde `204` e não \
                    devolve o assento duas vezes.""")
    @ApiResponse(responseCode = "204", description = "Reserva cancelada (ou já estava cancelada)")
    @ApiResponse(responseCode = "400", description = "Identificador fora do formato UUID",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemResponse.class),
                    examples = @ExampleObject(name = "INVALID_PARAMETER", ref = ApiExamples.RESERVATION_INVALID_PARAMETER)))
    @ApiResponse(responseCode = "404", description = "Reserva inexistente",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemResponse.class),
                    examples = @ExampleObject(name = "RESERVATION_NOT_FOUND", ref = ApiExamples.RESERVATION_NOT_FOUND)))
    @ApiResponse(responseCode = "409", description = "Reserva confirmada ou expirada não pode ser cancelada",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemResponse.class),
                    examples = @ExampleObject(name = "INVALID_RESERVATION_STATE", ref = ApiExamples.CANCEL_INVALID_RESERVATION_STATE)))
    ResponseEntity<Void> cancel(
            @Parameter(description = "Identificador da reserva", example = RESERVATION_ID_EXAMPLE)
            UUID id);
}
