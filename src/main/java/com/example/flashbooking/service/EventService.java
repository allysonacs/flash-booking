package com.example.flashbooking.service;

import com.example.flashbooking.dto.request.CreateEventRequest;
import com.example.flashbooking.dto.response.EventResponse;
import com.example.flashbooking.entity.Event;
import com.example.flashbooking.entity.EventInventory;
import com.example.flashbooking.exception.EventNotFoundException;
import com.example.flashbooking.repository.EventInventoryRepository;
import com.example.flashbooking.repository.EventRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso de evento.
 *
 * <p>É uma classe concreta, sem interface: há uma única implementação e nenhum ponto de
 * variação previsto. Criar a interface agora só adicionaria um arquivo a percorrer — ver
 * ADR-0001. Quando existir variação real, como no alocador de assentos da próxima fase, a
 * abstração entra porque paga o próprio custo.
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;
    private final EventInventoryRepository inventoryRepository;

    public EventService(EventRepository eventRepository, EventInventoryRepository inventoryRepository) {
        this.eventRepository = eventRepository;
        this.inventoryRepository = inventoryRepository;
    }

    /**
     * Cria o evento e o inventário correspondente.
     *
     * <p>As duas escritas acontecem na <strong>mesma transação</strong>: um evento sem
     * inventário seria um evento que nunca pode vender, e um inventário órfão seria
     * capacidade que ninguém reivindica. Não existe estado intermediário visível.
     */
    @Transactional
    public EventResponse create(CreateEventRequest request) {
        // saveAndFlush, e não save: o INSERT precisa acontecer agora para que o
        // created_at gerado pelo banco esteja disponível ao montar a resposta. Com o
        // flush adiado até o commit, o campo chegaria nulo ao cliente.
        Event event = eventRepository.saveAndFlush(new Event(request.name(), request.capacity()));
        EventInventory inventory = inventoryRepository.saveAndFlush(new EventInventory(event));

        log.info("Evento criado id={} capacidade={}", event.getId(), event.getTotalCapacity());
        return EventResponse.from(event, inventory);
    }

    /**
     * Consulta um evento e sua disponibilidade atual.
     *
     * <p>Nesta fase a disponibilidade é lida diretamente da fonte da verdade, e portanto é
     * fortemente consistente. A leitura passa a ser servida pela projeção eventualmente
     * consistente na Fase 5, quando o volume justificar — e o contrato dessa mudança está
     * documentado em ARCHITECTURE.md.
     */
    @Transactional(readOnly = true)
    public EventResponse findById(UUID eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));

        EventInventory inventory = inventoryRepository.findById(eventId)
                .orElseThrow(() -> new IllegalStateException(
                        "Inventário ausente para o evento %s".formatted(eventId)));

        return EventResponse.from(event, inventory);
    }
}
