package com.example.flashbooking.service;

import com.example.flashbooking.entity.Reservation;
import com.example.flashbooking.observability.ReservationMetrics;
import com.example.flashbooking.repository.ReservationRepository;
import com.example.flashbooking.service.allocation.SeatAllocator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Devolve ao inventário os assentos de reservas que venceram sem confirmação.
 *
 * <p>Cada lote é <strong>uma transação</strong>: reivindicar, transicionar e devolver
 * acontecem juntos ou não acontecem. Se a devolução falhar, a reserva continua
 * {@code PENDING} e a varredura seguinte tenta de novo — nunca fica um assento preso por uma
 * reserva morta, nem um assento devolvido por uma reserva que continuou viva.
 *
 * <p><strong>Idempotência:</strong> o processamento é seguro contra repetição em dois
 * níveis. A reivindicação só enxerga reservas {@code PENDING}, então uma reserva já expirada
 * não aparece de novo; e a transição carrega a mesma condição no {@code WHERE}, de modo que
 * aplicá-la duas vezes afeta zero linhas. Rodar a varredura duas vezes seguidas devolve os
 * assentos uma vez só.
 *
 * <p><strong>Corrida com o cancelamento:</strong> não há vencedor predefinido — vence quem
 * travar a linha primeiro. As duas operações partem de {@code PENDING} e as duas são
 * guardadas por esse estado, então exatamente uma se aplica e exatamente uma devolução
 * acontece. Se o cancelamento chega primeiro, a reserva vira {@code CANCELLED} e a varredura
 * simplesmente não a encontra mais; se a varredura chega primeiro, ela trava a linha, o
 * cancelamento fica bloqueado até o commit e então atualiza zero linhas — e responde
 * {@code 409}, porque cancelar uma reserva que já morreu não é uma operação válida.
 */
@Service
public class ReservationExpirationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirationService.class);

    private final ReservationRepository reservationRepository;
    private final SeatAllocator seatAllocator;
    private final ReservationMetrics metrics;

    public ReservationExpirationService(ReservationRepository reservationRepository,
                                        SeatAllocator seatAllocator,
                                        ReservationMetrics metrics) {
        this.reservationRepository = reservationRepository;
        this.seatAllocator = seatAllocator;
        this.metrics = metrics;
    }

    /**
     * Expira até {@code batchSize} reservas vencidas e devolve seus assentos.
     *
     * @return quantas reservas foram expiradas nesta chamada
     */
    @Transactional
    public int expireDueReservations(int batchSize) {
        List<UUID> claimed = reservationRepository.claimExpiredReservations(batchSize);
        if (claimed.isEmpty()) {
            return 0;
        }

        List<Reservation> reservations = reservationRepository.findAllById(claimed);
        int expired = reservationRepository.markExpired(claimed);

        if (expired != reservations.size()) {
            // Impossível enquanto os locks da reivindicação forem respeitados: as linhas
            // estão travadas por esta transação. Se acontecer, é sinal de que alguém passou
            // por fora da transição guardada — e a transação inteira é desfeita.
            throw new IllegalStateException(
                    "Lote inconsistente: %d reservas reivindicadas, %d transicionadas"
                            .formatted(reservations.size(), expired));
        }

        releaseSeats(reservations);
        metrics.reservationsExpired(expired,
                reservations.stream().mapToInt(Reservation::getQuantity).sum());
        return expired;
    }

    /**
     * Devolve os assentos agrupados por evento: uma reserva de 2 e outra de 3 do mesmo
     * evento viram um único {@code UPDATE} de 5. Menos comandos na linha quente significa
     * menos tempo de lock competindo com as vendas em andamento.
     */
    private void releaseSeats(List<Reservation> reservations) {
        Map<UUID, Integer> seatsByEvent = reservations.stream()
                .collect(Collectors.groupingBy(Reservation::getEventId,
                        Collectors.summingInt(Reservation::getQuantity)));

        seatsByEvent.forEach((eventId, seats) -> {
            if (!seatAllocator.release(eventId, seats)) {
                throw new IllegalStateException(
                        "Devolução de %d assento(s) recusada para o evento %s".formatted(seats, eventId));
            }
            log.info("Expiração devolveu {} assento(s) ao evento {}", seats, eventId);
        });
    }
}
