package com.example.flashbooking.service;

import com.example.flashbooking.dto.request.CreateReservationRequest;
import com.example.flashbooking.dto.response.ReservationResponse;
import com.example.flashbooking.entity.Reservation;
import com.example.flashbooking.entity.ReservationStatus;
import com.example.flashbooking.exception.EventNotOpenForReservationException;
import com.example.flashbooking.exception.IdempotencyKeyConflictException;
import com.example.flashbooking.exception.InsufficientCapacityException;
import com.example.flashbooking.exception.InvalidReservationStateException;
import com.example.flashbooking.exception.ReservationExpiredException;
import com.example.flashbooking.exception.ReservationNotFoundException;
import com.example.flashbooking.observability.ReservationMetrics;
import com.example.flashbooking.repository.ReservationRepository;
import com.example.flashbooking.service.allocation.SeatAllocator;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso de reserva.
 *
 * <p>Esta classe orquestra; quem transaciona a criação é {@link ReservationTxService}.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ReservationTxService reservationTxService;
    private final ReservationRepository reservationRepository;
    private final SeatAllocator seatAllocator;
    private final ReservationMetrics metrics;

    public ReservationService(ReservationTxService reservationTxService,
                              ReservationRepository reservationRepository,
                              SeatAllocator seatAllocator,
                              ReservationMetrics metrics) {
        this.reservationTxService = reservationTxService;
        this.reservationRepository = reservationRepository;
        this.seatAllocator = seatAllocator;
        this.metrics = metrics;
    }

    /**
     * Cria uma reserva, com semântica idempotente quando o cliente informa uma chave.
     *
     * <p><strong>Este método não pode ser transacional</strong>, e isso é parte do desenho:
     * ele precisa capturar a violação da constraint de idempotência, e uma transação que
     * sofreu violação de constraint no PostgreSQL já está abortada — nada mais pode ser
     * executado nela. Anotá-lo com {@code @Transactional} faria a captura ser inútil e o
     * commit falhar depois.
     *
     * <p>A corrida de idempotência é resolvida assim: duas requisições simultâneas com a
     * mesma chave consultam, não encontram nada e inserem. O PostgreSQL faz a segunda
     * <em>aguardar</em> no índice único até a primeira terminar — e só então lança a
     * violação, o que garante que, quando ela é capturada aqui, a reserva vencedora já está
     * commitada e visível. O perdedor então devolve a reserva do vencedor, e seu próprio
     * rollback libera os assentos que havia comprometido. Nenhuma janela, nenhuma
     * coordenação entre instâncias, nenhum estado em memória.
     */
    public ReservationCreationResult create(UUID eventId, CreateReservationRequest request,
                                            String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<Reservation> previous =
                    reservationRepository.findByEventIdAndIdempotencyKey(eventId, idempotencyKey);
            if (previous.isPresent()) {
                return replayOf(previous.get(), request, idempotencyKey);
            }
        }

        try {
            Reservation reservation =
                    reservationTxService.createReservation(eventId, request.quantity(), idempotencyKey);
            metrics.reservationCreated(reservation.getQuantity());
            log.info("Reserva criada id={} evento={} quantidade={}",
                    reservation.getId(), eventId, reservation.getQuantity());

            // Nada é publicado aqui. O evento de integração já foi gravado no outbox, dentro
            // da transação; quem o leva ao Kafka é o relay, depois do commit. Publicar deste
            // ponto seria anunciar uma venda que ainda pode ser desfeita.

            return ReservationCreationResult.created(ReservationResponse.from(reservation));

        } catch (InsufficientCapacityException soldOut) {
            // "Esgotado" é resultado normal de uma flash sale, e é indistinguível de uma
            // falha técnica nas métricas de HTTP: as duas são 409. Contar aqui é o que
            // permite responder "o evento vendeu tudo" sem abrir o log.
            metrics.reservationRejected("sold_out");
            throw soldOut;

        } catch (EventNotOpenForReservationException notOnSale) {
            metrics.reservationRejected("event_not_on_sale");
            throw notOnSale;

        } catch (DataIntegrityViolationException violation) {
            if (idempotencyKey == null) {
                throw violation;
            }
            Reservation winner = reservationRepository
                    .findByEventIdAndIdempotencyKey(eventId, idempotencyKey)
                    .orElseThrow(() -> violation);

            log.info("Requisição concorrente com a mesma chave de idempotência; devolvendo a reserva {}",
                    winner.getId());
            return replayOf(winner, request, idempotencyKey);
        }
    }

    /**
     * Devolve o resultado de uma requisição anterior com a mesma chave.
     *
     * <p>Antes disso, confere que o payload é o mesmo: chave repetida com quantidade
     * diferente não é retentativa, é outra intenção reusando a chave — e devolver a reserva
     * antiga como se fosse a nova esconderia do cliente que seu pedido não foi atendido.
     * Como o payload tem um único campo, comparar a quantidade já é a comparação completa;
     * quando o contrato crescer, entra um hash do payload canônico no lugar.
     */
    private ReservationCreationResult replayOf(Reservation reservation,
                                               CreateReservationRequest request,
                                               String idempotencyKey) {
        if (reservation.getQuantity() != request.quantity()) {
            throw new IdempotencyKeyConflictException(idempotencyKey);
        }
        return ReservationCreationResult.replayed(ReservationResponse.from(reservation));
    }

    @Transactional(readOnly = true)
    public ReservationResponse findById(UUID reservationId) {
        return reservationRepository.findById(reservationId)
                .map(ReservationResponse::from)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
    }

    /**
     * Confirma uma reserva pendente: os assentos deixam de ter prazo e a venda está feita.
     *
     * <p><strong>O inventário não é tocado.</strong> Os assentos já foram comprometidos na
     * criação da reserva; confirmar não reserva nada de novo, apenas impede que eles voltem
     * ao estoque. É por isso que esta é a única transição que sai de {@code PENDING} sem
     * mexer no contador do evento — e a razão de a disponibilidade não mudar quando o cliente
     * confirma.
     *
     * <p>As duas guardas que tornam a operação segura estão no {@code UPDATE}
     * ({@code ReservationRepository#confirmIfPending}), e não aqui: a de estado, que faz
     * exatamente uma confirmação se aplicar entre requisições simultâneas, e a de prazo, que
     * impede a confirmação de atravessar um TTL já vencido enquanto a varredura de expiração
     * não chegou. Checar o prazo neste método, com o relógio da aplicação, apenas adiantaria
     * um palpite — a decisão pertence ao mesmo relógio que a varredura usa, o do banco.
     *
     * <p>O método é idempotente: confirmar uma reserva já confirmada devolve a reserva e não
     * faz nada, porque o estado desejado pelo cliente já é o estado atual.
     *
     * @return a reserva no estado em que ficou
     */
    @Transactional
    public ReservationResponse confirm(UUID reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            return ReservationResponse.from(reservation);
        }
        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new InvalidReservationStateException(
                    reservationId, reservation.getStatus(), "confirmar");
        }

        if (reservationRepository.confirmIfPending(reservationId) == 0) {
            return onLostConfirmationRace(reservationId);
        }

        metrics.reservationConfirmed(reservation.getQuantity());
        log.info("Reserva confirmada id={} ingressos={}", reservationId, reservation.getQuantity());
        return reload(reservationId);
    }

    /**
     * Decide a resposta de uma confirmação que afetou zero linhas.
     *
     * <p>Zero linhas tem duas causas possíveis, e a releitura da linha é o que as separa —
     * sem ela a resposta dependeria de qual guarda do {@code WHERE} falhou, coisa que o
     * {@code UPDATE} não informa.
     *
     * <ul>
     *   <li>A reserva <strong>ainda está {@code PENDING}</strong>: quem recusou foi a guarda
     *       de prazo. O TTL venceu e a varredura de expiração simplesmente não passou por ela
     *       ainda. É {@code RESERVATION_EXPIRED} — o desfecho é o mesmo de uma reserva
     *       expirada, e dizer "estado inválido: PENDING" seria uma contradição.
     *   <li>A reserva <strong>mudou de estado</strong> enquanto esta transação esperava no
     *       lock: outra confirmação (devolve sucesso, o estado pedido é o atual), ou um
     *       cancelamento ou a expiração (devolve {@code 409}).
     * </ul>
     */
    private ReservationResponse onLostConfirmationRace(UUID reservationId) {
        Reservation current = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        if (current.getStatus() == ReservationStatus.CONFIRMED) {
            log.debug("Reserva {} já havia sido confirmada por outra requisição", reservationId);
            return ReservationResponse.from(current);
        }
        if (current.getStatus() == ReservationStatus.PENDING) {
            log.debug("Confirmação da reserva {} chegou depois do prazo", reservationId);
            throw new ReservationExpiredException(reservationId);
        }

        log.debug("Confirmação da reserva {} perdeu a corrida para a transição {}",
                reservationId, current.getStatus());
        throw new InvalidReservationStateException(reservationId, current.getStatus(), "confirmar");
    }

    /**
     * Relê a reserva depois de uma transição. O {@code UPDATE} é nativo e limpa o contexto de
     * persistência, então a instância carregada antes dele ficou com o estado antigo —
     * devolvê-la ao cliente mostraria {@code PENDING} logo depois de confirmar.
     */
    private ReservationResponse reload(UUID reservationId) {
        return reservationRepository.findById(reservationId)
                .map(ReservationResponse::from)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
    }

    /**
     * Cancela uma reserva pendente e devolve os assentos ao inventário.
     *
     * <p>A devolução é <em>guardada</em> pela transição de estado: os assentos só voltam
     * para quem conseguiu mudar a reserva de {@code PENDING} para {@code CANCELLED}. Dois
     * cancelamentos simultâneos da mesma reserva disputam essa transição no banco; o
     * perdedor atualiza zero linhas e não devolve nada. Sem essa guarda, o mesmo assento
     * seria devolvido duas vezes — e capacidade criada do nada é a outra face do oversell.
     *
     * <p>O método é idempotente: cancelar uma reserva já cancelada é uma operação bem
     * sucedida que não faz nada, porque o estado desejado pelo cliente já é o estado atual.
     */
    @Transactional
    public void cancel(UUID reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            return;
        }
        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new InvalidReservationStateException(
                    reservationId, reservation.getStatus(), "cancelar");
        }

        if (reservationRepository.cancelIfPending(reservationId) == 0) {
            onLostCancellationRace(reservationId);
            return;
        }

        seatAllocator.release(reservation.getEventId(), reservation.getQuantity());
        metrics.reservationCancelled(reservation.getQuantity());
        log.info("Reserva cancelada id={} assentos devolvidos={}",
                reservationId, reservation.getQuantity());
    }

    /**
     * Decide a resposta de um cancelamento que afetou zero linhas.
     *
     * <p>Zero linhas significa que outra transação aplicou uma transição a partir de
     * {@code PENDING} enquanto esta esperava no lock da linha — outro cancelamento, ou a
     * varredura de expiração. A verificação de estado feita no início do método pode ter
     * lido a reserva antes disso, então a releitura aqui é o que torna a resposta
     * <strong>determinística</strong>: sem ela, cancelar uma reserva que acabou de expirar
     * responderia {@code 204} ou {@code 409} conforme o instante em que a leitura caiu.
     *
     * <p>Quem perdeu para outro cancelamento recebe sucesso — o estado pedido é o estado
     * atual. Quem perdeu para a expiração recebe {@code 409}: a reserva morreu, e dizer ao
     * cliente que o cancelamento deu certo esconderia dele que seu pedido não foi atendido.
     * Em nenhum dos dois casos os assentos são devolvidos de novo.
     */
    private void onLostCancellationRace(UUID reservationId) {
        ReservationStatus current = reservationRepository.findById(reservationId)
                .map(Reservation::getStatus)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        if (current == ReservationStatus.CANCELLED) {
            log.debug("Reserva {} já havia sido cancelada por outra requisição", reservationId);
            return;
        }
        log.debug("Cancelamento da reserva {} perdeu a corrida para a transição {}",
                reservationId, current);
        throw new InvalidReservationStateException(reservationId, current, "cancelar");
    }
}
