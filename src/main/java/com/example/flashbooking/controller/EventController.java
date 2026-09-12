package com.example.flashbooking.controller;

import com.example.flashbooking.dto.request.CreateEventRequest;
import com.example.flashbooking.dto.response.EventResponse;
import com.example.flashbooking.service.EventService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de eventos.
 *
 * <p>O controller não decide nada: valida o formato da entrada, delega ao serviço e escolhe
 * o status HTTP. Erros não são tratados aqui — sobem para o
 * {@link com.example.flashbooking.exception.GlobalExceptionHandler}, que é o único lugar do
 * sistema que converte falha em resposta.
 *
 * <p>A documentação OpenAPI e as restrições de validação da entrada estão em {@link EventApi}.
 */
@RestController
@RequestMapping("/events")
public class EventController implements EventApi {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    /**
     * Cria um evento.
     *
     * <p>Responde {@code 201 Created} com o recurso no corpo e o header {@code Location}
     * apontando para ele — o cliente não precisa montar a URL de consulta por conta própria.
     */
    @Override
    @PostMapping
    public ResponseEntity<EventResponse> create(@RequestBody CreateEventRequest request) {
        EventResponse event = eventService.create(request);
        return ResponseEntity.created(URI.create("/events/" + event.id())).body(event);
    }

    /** Consulta um evento e sua disponibilidade atual. */
    @Override
    @GetMapping("/{id}")
    public EventResponse findById(@PathVariable UUID id) {
        return eventService.findById(id);
    }
}
