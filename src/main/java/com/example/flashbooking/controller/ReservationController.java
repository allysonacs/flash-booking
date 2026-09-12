package com.example.flashbooking.controller;

import com.example.flashbooking.dto.request.CreateReservationRequest;
import com.example.flashbooking.dto.response.ReservationResponse;
import com.example.flashbooking.service.ReservationCreationResult;
import com.example.flashbooking.service.ReservationService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de reservas.
 *
 * <p>A documentação OpenAPI e as restrições de validação da entrada ({@code @Valid},
 * {@code @Size}) estão em {@link ReservationApi}: o Bean Validation proíbe que um método
 * sobrescrito redefina as restrições do método que implementa, então elas vivem em um só
 * lugar — o contrato.
 */
@RestController
public class ReservationController implements ReservationApi {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /**
     * Cria uma reserva para o evento.
     *
     * <p>O header {@code Idempotency-Key} é opcional, mas é o que torna a retentativa
     * segura: com ele, repetir a requisição devolve a reserva original em vez de criar outra.
     * Uma retentativa responde {@code 200 OK}, e não {@code 201 Created} — nada foi criado
     * desta vez.
     */
    @Override
    @PostMapping("/events/{eventId}/reservations")
    public ResponseEntity<ReservationResponse> create(
            @PathVariable UUID eventId,
            @RequestBody CreateReservationRequest request,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {

        ReservationCreationResult result = reservationService.create(eventId, request, idempotencyKey);
        URI location = URI.create("/reservations/" + result.reservation().id());

        return result.replayed()
                ? ResponseEntity.ok().location(location).body(result.reservation())
                : ResponseEntity.created(location).body(result.reservation());
    }

    @Override
    @GetMapping("/reservations/{id}")
    public ReservationResponse findById(@PathVariable UUID id) {
        return reservationService.findById(id);
    }

    /**
     * Confirma a reserva: os ingressos passam a ser do cliente e deixam de ter prazo.
     *
     * <p>A confirmação é modelada como um sub-recurso, e não como um {@code PATCH} de status:
     * o cliente pede <em>a confirmação desta reserva</em>, sem escolher para qual estado ela
     * vai — as transições de uma reserva não são um campo editável.
     *
     * <p>Responde {@code 200 OK} com a reserva no estado resultante, inclusive ao repetir
     * sobre uma reserva já confirmada — é o mesmo critério do {@code DELETE}: o cliente pediu
     * um estado, e esse estado é o atual. Os desfechos de falha são {@code 404}
     * ({@code RESERVATION_NOT_FOUND}), {@code 409 RESERVATION_EXPIRED} quando o prazo venceu
     * e {@code 409 INVALID_RESERVATION_STATE} quando a reserva já foi cancelada ou expirada.
     */
    @Override
    @PostMapping("/reservations/{id}/confirmation")
    public ReservationResponse confirm(@PathVariable UUID id) {
        return reservationService.confirm(id);
    }

    /**
     * Cancela uma reserva e devolve os assentos.
     *
     * <p>Responde {@code 204 No Content} também quando a reserva já estava cancelada: o
     * cliente pediu um estado, e esse estado é o atual. Repetir o {@code DELETE} não devolve
     * assento duas vezes.
     */
    @Override
    @DeleteMapping("/reservations/{id}")
    public ResponseEntity<Void> cancel(@PathVariable UUID id) {
        reservationService.cancel(id);
        return ResponseEntity.noContent().build();
    }
}
