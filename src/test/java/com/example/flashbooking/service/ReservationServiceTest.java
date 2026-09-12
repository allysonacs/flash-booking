package com.example.flashbooking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.flashbooking.dto.request.CreateReservationRequest;
import com.example.flashbooking.dto.response.ReservationResponse;
import com.example.flashbooking.entity.Reservation;
import com.example.flashbooking.entity.ReservationStatus;
import com.example.flashbooking.exception.IdempotencyKeyConflictException;
import com.example.flashbooking.exception.InvalidReservationStateException;
import com.example.flashbooking.exception.ReservationExpiredException;
import com.example.flashbooking.exception.ReservationNotFoundException;
import com.example.flashbooking.observability.ReservationMetrics;
import com.example.flashbooking.repository.ReservationRepository;
import com.example.flashbooking.service.allocation.SeatAllocator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Testes de unidade da orquestração da reserva: replay de idempotência, tradução de corridas
 * e as guardas das transições — confirmação e cancelamento.
 *
 * <p>O que está sob teste aqui são as <em>decisões</em>. Que o inventário não é vendido duas
 * vezes é provado contra um PostgreSQL real, com threads de verdade, em
 * {@code ReservationConcurrencyIntegrationTest} — nenhum mock consegue provar aquilo.
 */
@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final String IDEMPOTENCY_KEY = "chave-do-cliente-1";

    @Mock
    private ReservationTxService reservationTxService;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private SeatAllocator seatAllocator;

    @Mock
    private ReservationMetrics metrics;

    @InjectMocks
    private ReservationService reservationService;

    @Test
    @DisplayName("sem chave de idempotência, cada requisição cria uma reserva nova")
    void createsReservationWithoutIdempotencyKey() {
        Reservation reservation = pendingReservation(2);
        when(reservationTxService.createReservation(EVENT_ID, 2, null)).thenReturn(reservation);

        ReservationCreationResult result =
                reservationService.create(EVENT_ID, new CreateReservationRequest(2), null);

        assertThat(result.replayed()).isFalse();
        assertThat(result.reservation().id()).isEqualTo(reservation.getId());
        verifyNoInteractions(reservationRepository);
    }

    @Test
    @DisplayName("chave já usada com o mesmo payload devolve a reserva original, sem criar outra")
    void replaysPreviousReservationForRepeatedKey() {
        Reservation original = pendingReservation(2);
        when(reservationRepository.findByEventIdAndIdempotencyKey(EVENT_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(original));

        ReservationCreationResult result = reservationService.create(
                EVENT_ID, new CreateReservationRequest(2), IDEMPOTENCY_KEY);

        assertThat(result.replayed()).isTrue();
        assertThat(result.reservation().id()).isEqualTo(original.getId());
        verify(reservationTxService, never()).createReservation(any(), anyInt(), any());
    }

    @Test
    @DisplayName("chave já usada com payload diferente é conflito, não retentativa")
    void rejectsKeyReuseWithDifferentPayload() {
        when(reservationRepository.findByEventIdAndIdempotencyKey(EVENT_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(pendingReservation(2)));

        assertThatThrownBy(() -> reservationService.create(
                EVENT_ID, new CreateReservationRequest(5), IDEMPOTENCY_KEY))
                .isInstanceOf(IdempotencyKeyConflictException.class)
                .hasMessageContaining(IDEMPOTENCY_KEY);

        verify(reservationTxService, never()).createReservation(any(), anyInt(), any());
    }

    @Test
    @DisplayName("duas requisições simultâneas com a mesma chave: a perdedora devolve a reserva vencedora")
    void resolvesConcurrentKeyRaceByReplayingTheWinner() {
        Reservation winner = pendingReservation(2);
        when(reservationRepository.findByEventIdAndIdempotencyKey(EVENT_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(reservationTxService.createReservation(EVENT_ID, 2, IDEMPOTENCY_KEY))
                .thenThrow(new DataIntegrityViolationException("ux_reservations_event_idempotency_key"));

        ReservationCreationResult result = reservationService.create(
                EVENT_ID, new CreateReservationRequest(2), IDEMPOTENCY_KEY);

        assertThat(result.replayed()).isTrue();
        assertThat(result.reservation().id()).isEqualTo(winner.getId());
    }

    @Test
    @DisplayName("violação de integridade sem chave de idempotência não é mascarada")
    void rethrowsIntegrityViolationWhenThereIsNoKeyToReplay() {
        when(reservationTxService.createReservation(EVENT_ID, 1, null))
                .thenThrow(new DataIntegrityViolationException("fk_reservations_event"));

        assertThatThrownBy(() -> reservationService.create(
                EVENT_ID, new CreateReservationRequest(1), null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("confirmar uma reserva pendente não devolve nem reserva assentos de novo")
    void confirmDoesNotTouchInventory() {
        Reservation reservation = pendingReservation(3);
        UUID reservationId = reservation.getId();
        when(reservationRepository.findById(reservationId))
                .thenReturn(Optional.of(reservation))
                .thenReturn(Optional.of(sameReservationWith(reservation, ReservationStatus.CONFIRMED)));
        when(reservationRepository.confirmIfPending(reservationId)).thenReturn(1);

        ReservationResponse confirmed = reservationService.confirm(reservationId);

        assertThat(confirmed.status()).isEqualTo(ReservationStatus.CONFIRMED);
        // Os assentos já estavam comprometidos desde a criação: confirmar não mexe no estoque.
        verifyNoInteractions(seatAllocator);
        verify(metrics).reservationConfirmed(3);
    }

    @Test
    @DisplayName("confirmar uma reserva já confirmada devolve a reserva e não conta de novo")
    void confirmIsIdempotent() {
        Reservation reservation = reservationWithStatus(3, ReservationStatus.CONFIRMED);
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));

        ReservationResponse confirmed = reservationService.confirm(reservation.getId());

        assertThat(confirmed.status()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(reservationRepository, never()).confirmIfPending(any());
        verify(metrics, never()).reservationConfirmed(anyInt());
    }

    @Test
    @DisplayName("confirmação recusada pelo prazo, com a reserva ainda PENDING, é reserva expirada")
    void confirmRejectsWhenDeadlineHasPassed() {
        Reservation reservation = pendingReservation(2);
        UUID reservationId = reservation.getId();
        // A reserva continua PENDING na releitura: quem recusou o UPDATE foi a guarda de
        // prazo, e não a de estado — a varredura de expiração ainda não passou por ela.
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(reservationRepository.confirmIfPending(reservationId)).thenReturn(0);

        assertThatThrownBy(() -> reservationService.confirm(reservationId))
                .isInstanceOf(ReservationExpiredException.class);

        verifyNoInteractions(seatAllocator);
        verify(metrics, never()).reservationConfirmed(anyInt());
    }

    @Test
    @DisplayName("quem perde a corrida para outra confirmação recebe sucesso, não conflito")
    void confirmDoesNotFailWhenAnotherConfirmationWon() {
        Reservation reservation = pendingReservation(2);
        UUID reservationId = reservation.getId();
        when(reservationRepository.findById(reservationId))
                .thenReturn(Optional.of(reservation))
                .thenReturn(Optional.of(sameReservationWith(reservation, ReservationStatus.CONFIRMED)));
        when(reservationRepository.confirmIfPending(reservationId)).thenReturn(0);

        assertThat(reservationService.confirm(reservationId).status())
                .isEqualTo(ReservationStatus.CONFIRMED);

        verify(metrics, never()).reservationConfirmed(anyInt());
    }

    @Test
    @DisplayName("quem perde a corrida para o cancelamento não consegue confirmar")
    void confirmRejectsWhenCancellationWonTheRace() {
        Reservation reservation = pendingReservation(2);
        UUID reservationId = reservation.getId();
        when(reservationRepository.findById(reservationId))
                .thenReturn(Optional.of(reservation))
                .thenReturn(Optional.of(sameReservationWith(reservation, ReservationStatus.CANCELLED)));
        when(reservationRepository.confirmIfPending(reservationId)).thenReturn(0);

        assertThatThrownBy(() -> reservationService.confirm(reservationId))
                .isInstanceOf(InvalidReservationStateException.class)
                .hasMessageContaining("CANCELLED");
    }

    @Test
    @DisplayName("não é possível confirmar uma reserva expirada")
    void confirmRejectsInvalidState() {
        Reservation reservation = reservationWithStatus(1, ReservationStatus.EXPIRED);
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> reservationService.confirm(reservation.getId()))
                .isInstanceOf(InvalidReservationStateException.class)
                .hasMessageContaining("EXPIRED");

        verify(reservationRepository, never()).confirmIfPending(any());
    }

    @Test
    @DisplayName("uma reserva confirmada não pode mais ser cancelada")
    void cancelRejectsConfirmedReservation() {
        Reservation reservation = reservationWithStatus(2, ReservationStatus.CONFIRMED);
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> reservationService.cancel(reservation.getId()))
                .isInstanceOf(InvalidReservationStateException.class)
                .hasMessageContaining("CONFIRMED");

        verify(seatAllocator, never()).release(any(), anyInt());
    }

    @Test
    @DisplayName("cancelar uma reserva pendente devolve os assentos")
    void cancelReleasesSeats() {
        Reservation reservation = pendingReservation(3);
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(reservationRepository.cancelIfPending(reservation.getId())).thenReturn(1);

        reservationService.cancel(reservation.getId());

        verify(seatAllocator).release(EVENT_ID, 3);
    }

    @Test
    @DisplayName("cancelar uma reserva já cancelada não devolve assentos de novo")
    void cancelIsIdempotent() {
        Reservation reservation = reservationWithStatus(3, ReservationStatus.CANCELLED);
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));

        reservationService.cancel(reservation.getId());

        verify(reservationRepository, never()).cancelIfPending(any());
        verify(seatAllocator, never()).release(any(), anyInt());
    }

    @Test
    @DisplayName("quem perde a corrida para outro cancelamento não devolve assentos")
    void cancelDoesNotReleaseSeatsWhenAnotherCancellationWon() {
        Reservation reservation = pendingReservation(3);
        UUID reservationId = reservation.getId();
        when(reservationRepository.findById(reservationId))
                .thenReturn(Optional.of(reservation))
                .thenReturn(Optional.of(sameReservationWith(reservation, ReservationStatus.CANCELLED)));
        when(reservationRepository.cancelIfPending(reservationId)).thenReturn(0);

        // Sucesso: o estado que o cliente pediu já é o estado atual.
        reservationService.cancel(reservationId);

        verify(seatAllocator, never()).release(any(), anyInt());
    }

    @Test
    @DisplayName("quem perde a corrida para a expiração recebe conflito, e não um sucesso enganoso")
    void cancelRejectsWhenExpirationWonTheRace() {
        Reservation reservation = pendingReservation(3);
        UUID reservationId = reservation.getId();
        when(reservationRepository.findById(reservationId))
                .thenReturn(Optional.of(reservation))
                .thenReturn(Optional.of(sameReservationWith(reservation, ReservationStatus.EXPIRED)));
        when(reservationRepository.cancelIfPending(reservationId)).thenReturn(0);

        // A reserva estava PENDING quando foi lida e expirou enquanto este cancelamento
        // esperava no lock. A releitura torna a resposta a mesma que teria sido se a
        // expiração tivesse acontecido um instante antes: 409, e nunca uma segunda
        // devolução de assentos.
        assertThatThrownBy(() -> reservationService.cancel(reservationId))
                .isInstanceOf(InvalidReservationStateException.class)
                .hasMessageContaining("EXPIRED");

        verify(seatAllocator, never()).release(any(), anyInt());
    }

    @Test
    @DisplayName("não é possível cancelar uma reserva expirada")
    void cancelRejectsInvalidState() {
        Reservation reservation = reservationWithStatus(1, ReservationStatus.EXPIRED);
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> reservationService.cancel(reservation.getId()))
                .isInstanceOf(InvalidReservationStateException.class)
                .hasMessageContaining("EXPIRED");

        verify(seatAllocator, never()).release(any(), anyInt());
    }

    @Test
    @DisplayName("cancelar ou consultar uma reserva inexistente resulta em recurso não encontrado")
    void unknownReservationIsReported() {
        UUID unknownId = UUID.randomUUID();
        when(reservationRepository.findById(eq(unknownId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.findById(unknownId))
                .isInstanceOf(ReservationNotFoundException.class);
        assertThatThrownBy(() -> reservationService.cancel(unknownId))
                .isInstanceOf(ReservationNotFoundException.class);
        assertThatThrownBy(() -> reservationService.confirm(unknownId))
                .isInstanceOf(ReservationNotFoundException.class);
    }

    private static Reservation pendingReservation(int quantity) {
        return reservationWithStatus(quantity, ReservationStatus.PENDING);
    }

    /** A mesma reserva relida do banco depois que outra transação mudou seu estado. */
    private static Reservation sameReservationWith(Reservation reservation, ReservationStatus status) {
        Reservation reloaded = reservationWithStatus(reservation.getQuantity(), status);
        ReflectionTestUtils.setField(reloaded, "id", reservation.getId());
        return reloaded;
    }

    private static Reservation reservationWithStatus(int quantity, ReservationStatus status) {
        Reservation reservation = new Reservation(
                EVENT_ID, quantity, IDEMPOTENCY_KEY, Instant.now().plus(15, ChronoUnit.MINUTES));
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(reservation, "status", status);
        return reservation;
    }
}
