package com.example.flashbooking.service;

import com.example.flashbooking.config.ReservationProperties;
import com.example.flashbooking.entity.Event;
import com.example.flashbooking.entity.EventStatus;
import com.example.flashbooking.entity.Reservation;
import com.example.flashbooking.exception.EventNotFoundException;
import com.example.flashbooking.exception.EventNotOpenForReservationException;
import com.example.flashbooking.exception.InsufficientCapacityException;
import com.example.flashbooking.messaging.OutboxRecorder;
import com.example.flashbooking.repository.EventRepository;
import com.example.flashbooking.repository.ReservationRepository;
import com.example.flashbooking.service.allocation.SeatAllocator;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A unidade transacional da reserva: grava a reserva e compromete os assentos, tudo ou nada.
 *
 * <p><strong>Por que esta classe existe separada de {@link ReservationService}:</strong> uma
 * violação de constraint no PostgreSQL aborta a transação inteira — depois dela, nenhum
 * comando é aceito até o rollback. Como a corrida de idempotência é resolvida justamente
 * capturando essa violação, quem a captura precisa estar <em>fora</em> da transação que
 * falhou. Daí a separação: aqui dentro é a transação; lá fora é quem lida com o resultado
 * dela. Chamar um método anotado com {@code @Transactional} da mesma classe também não
 * funcionaria — a chamada interna não passa pelo proxy do Spring.
 *
 * <p>Isolamento: {@code READ COMMITTED}, o padrão do PostgreSQL. Não é necessário elevar
 * para {@code SERIALIZABLE}, porque a decisão de vender não depende de nenhuma leitura feita
 * pela aplicação — ela é um {@code UPDATE} condicional avaliado pelo banco sob o lock da
 * linha.
 */
@Service
public class ReservationTxService {

    private final EventRepository eventRepository;
    private final ReservationRepository reservationRepository;
    private final SeatAllocator seatAllocator;
    private final OutboxRecorder outboxRecorder;
    private final ReservationProperties properties;
    private final Clock clock;

    public ReservationTxService(EventRepository eventRepository,
                                ReservationRepository reservationRepository,
                                SeatAllocator seatAllocator,
                                OutboxRecorder outboxRecorder,
                                ReservationProperties properties,
                                Clock clock) {
        this.eventRepository = eventRepository;
        this.reservationRepository = reservationRepository;
        this.seatAllocator = seatAllocator;
        this.outboxRecorder = outboxRecorder;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Cria a reserva e compromete os assentos em uma única transação.
     *
     * <p>A ordem das escritas é deliberada. A reserva é inserida e <em>flushada</em>
     * primeiro, o que faz a violação de chave de idempotência acontecer antes de tocar o
     * inventário; o evento de integração vai para o outbox em seguida; e o {@code UPDATE} da
     * linha quente fica por último, reduzindo o tempo em que o lock dessa linha fica retido —
     * sob flash sale, esse tempo é o que limita o throughput do evento.
     *
     * <p>Se não houver disponibilidade, a exceção desfaz também o {@code INSERT} da reserva:
     * não sobra reserva sem assento.
     */
    @Transactional
    public Reservation createReservation(UUID eventId, int quantity, String idempotencyKey) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));

        if (event.getStatus() != EventStatus.ON_SALE) {
            throw new EventNotOpenForReservationException(eventId, event.getStatus());
        }

        Instant expiresAt = Instant.now(clock).plus(properties.ttl());
        Reservation reservation = reservationRepository.saveAndFlush(
                new Reservation(eventId, quantity, idempotencyKey, expiresAt));

        // O evento de integração entra aqui, na mesma transação: ou a reserva e o evento
        // existem, ou nenhum dos dois. É o que elimina o dual write — ver OutboxRecorder.
        outboxRecorder.recordReservationCreated(reservation);

        if (!seatAllocator.tryAllocate(eventId, quantity)) {
            throw new InsufficientCapacityException(eventId, quantity);
        }
        return reservation;
    }
}
