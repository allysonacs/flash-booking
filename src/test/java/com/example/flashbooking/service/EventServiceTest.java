package com.example.flashbooking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.flashbooking.dto.request.CreateEventRequest;
import com.example.flashbooking.dto.response.EventResponse;
import com.example.flashbooking.entity.Event;
import com.example.flashbooking.entity.EventInventory;
import com.example.flashbooking.entity.EventStatus;
import com.example.flashbooking.exception.EventNotFoundException;
import com.example.flashbooking.repository.EventInventoryRepository;
import com.example.flashbooking.repository.EventRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Testes de unidade das regras de evento, com os repositórios simulados.
 *
 * <p>Mock aqui é legítimo porque o objeto sob teste é a <em>regra</em>, não a persistência:
 * o que se verifica é o que o serviço decide e com o quê chama o repositório. Que o dado
 * chega ao PostgreSQL é provado por outro teste, contra um banco real.
 */
@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private EventInventoryRepository inventoryRepository;

    @InjectMocks
    private EventService eventService;

    @Test
    @DisplayName("criar um evento grava o evento e o inventário zerado")
    void createPersistsEventAndInventory() {
        when(eventRepository.saveAndFlush(any(Event.class))).thenAnswer(call -> withId(call.getArgument(0)));
        when(inventoryRepository.saveAndFlush(any(EventInventory.class))).thenAnswer(call -> call.getArgument(0));

        EventResponse response = eventService.create(new CreateEventRequest("Show de Rock", 300));

        assertThat(response.id()).isNotNull();
        assertThat(response.name()).isEqualTo("Show de Rock");
        assertThat(response.status()).isEqualTo(EventStatus.ON_SALE);
        assertThat(response.totalCapacity()).isEqualTo(300);
        assertThat(response.reservedCount()).isZero();
        assertThat(response.availableCapacity()).isEqualTo(300);

        verify(eventRepository).saveAndFlush(any(Event.class));
        verify(inventoryRepository).saveAndFlush(any(EventInventory.class));
    }

    @Test
    @DisplayName("capacidade não positiva é recusada pela própria entidade, antes de qualquer escrita")
    void createRejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> eventService.create(new CreateEventRequest("Evento Inválido", 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalCapacity");

        verify(eventRepository, never()).saveAndFlush(any());
        verify(inventoryRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("consultar um evento devolve a disponibilidade calculada")
    void findByIdReturnsDerivedAvailability() {
        Event event = withId(new Event("Festival", 100));
        EventInventory inventory = new EventInventory(event);
        ReflectionTestUtils.setField(inventory, "reservedCount", 40);

        when(eventRepository.findById(event.getId())).thenReturn(Optional.of(event));
        when(inventoryRepository.findById(event.getId())).thenReturn(Optional.of(inventory));

        EventResponse response = eventService.findById(event.getId());

        assertThat(response.totalCapacity()).isEqualTo(100);
        assertThat(response.reservedCount()).isEqualTo(40);
        assertThat(response.availableCapacity()).isEqualTo(60);
    }

    @Test
    @DisplayName("consultar um evento inexistente lança EventNotFoundException")
    void findByIdFailsWhenEventIsUnknown() {
        UUID unknownId = UUID.randomUUID();
        when(eventRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.findById(unknownId))
                .isInstanceOf(EventNotFoundException.class)
                .hasMessageContaining(unknownId.toString());
    }

    @Test
    @DisplayName("evento sem inventário é defeito de dados, não 'não encontrado'")
    void findByIdFailsLoudlyWhenInventoryIsMissing() {
        Event event = withId(new Event("Evento Órfão", 10));
        when(eventRepository.findById(event.getId())).thenReturn(Optional.of(event));
        when(inventoryRepository.findById(event.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.findById(event.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Inventário ausente");
    }

    /** Simula o id que o Hibernate atribuiria no momento do persist. */
    private static Event withId(Event event) {
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        return event;
    }
}
