package com.example.flashbooking.controller;

import com.example.flashbooking.config.ApiExamples;
import com.example.flashbooking.config.OpenApiConfig;
import com.example.flashbooking.dto.request.CreateEventRequest;
import com.example.flashbooking.dto.response.EventResponse;
import com.example.flashbooking.dto.response.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

/**
 * Contrato documentado da API de eventos.
 *
 * <p>Documentação OpenAPI e restrições de validação da entrada. Mapeamento de rota e
 * comportamento ficam no {@link EventController}, que implementa esta interface. Os exemplos de
 * erro são objetos construídos em {@link ApiExamples} e referenciados aqui pelo nome.
 */
@Tag(name = OpenApiConfig.EVENTS_TAG)
public interface EventApi {

    @Operation(
            summary = "Cria um evento",
            description = """
                    Cria um evento com capacidade fixa, já aberto para venda (`ON_SALE`). A \
                    resposta traz o recurso no corpo e o header `Location` apontando para ele.""")
    @ApiResponse(responseCode = "201", description = "Evento criado",
            headers = @Header(name = "Location", description = "URL do evento criado",
                    schema = @Schema(type = "string", example = "/events/7f000001-a093-12b9-81a0-9312c1270000")))
    @ApiResponse(responseCode = "400", description = "Corpo ausente, malformado ou com campos inválidos",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemResponse.class),
                    examples = @ExampleObject(name = "VALIDATION_ERROR", ref = ApiExamples.EVENT_VALIDATION_ERROR)))
    ResponseEntity<EventResponse> create(@Valid CreateEventRequest request);

    @Operation(
            summary = "Consulta um evento e sua disponibilidade",
            description = """
                    `availableCapacity` é calculado a cada leitura (`totalCapacity - reservedCount`), \
                    nunca lido de uma coluna. Reservas `PENDING` e `CONFIRMED` ocupam assentos; \
                    `CANCELLED` e `EXPIRED` já os devolveram.""")
    @ApiResponse(responseCode = "200", description = "Evento encontrado")
    @ApiResponse(responseCode = "400", description = "Identificador fora do formato UUID",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemResponse.class),
                    examples = @ExampleObject(name = "INVALID_PARAMETER", ref = ApiExamples.EVENT_INVALID_PARAMETER)))
    @ApiResponse(responseCode = "404", description = "Evento inexistente",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemResponse.class),
                    examples = @ExampleObject(name = "EVENT_NOT_FOUND", ref = ApiExamples.EVENT_NOT_FOUND)))
    EventResponse findById(
            @Parameter(description = "Identificador do evento", example = "7f000001-a093-12b9-81a0-9312c1270000")
            UUID id);
}
