package com.example.flashbooking.repository;

import com.example.flashbooking.entity.Reservation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Acesso a {@link Reservation}. */
@Repository
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    /** Busca o resultado de uma requisição anterior com a mesma chave de idempotência. */
    Optional<Reservation> findByEventIdAndIdempotencyKey(UUID eventId, String idempotencyKey);

    /**
     * Transiciona a reserva de {@code PENDING} para {@code CANCELLED}, se ela ainda estiver
     * pendente.
     *
     * <p>A guarda no {@code WHERE} é o que torna o cancelamento seguro sob concorrência:
     * exatamente uma requisição consegue aplicar a transição, e só ela devolve os assentos.
     * Um segundo {@code DELETE} simultâneo atualiza zero linhas e não devolve nada — sem
     * isso, dois cancelamentos da mesma reserva devolveriam o assento duas vezes e criariam
     * capacidade do nada.
     *
     * @return 1 quando esta chamada aplicou a transição, 0 quando ela já havia sido aplicada
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE reservations
               SET status = 'CANCELLED',
                   updated_at = now()
             WHERE id = :reservationId
               AND status = 'PENDING'
            """, nativeQuery = true)
    int cancelIfPending(@Param("reservationId") UUID reservationId);

    /**
     * Transiciona a reserva de {@code PENDING} para {@code CONFIRMED}, se ela ainda estiver
     * pendente <strong>e dentro do prazo</strong>.
     *
     * <p>A guarda de estado é a mesma do cancelamento, e pelo mesmo motivo: exatamente uma
     * requisição aplica a transição. A guarda de prazo — {@code expires_at > now()} — é o que
     * esta transição tem de particular, e ela não é uma checagem redundante com a varredura
     * de expiração.
     *
     * <p>Sem ela existiria esta janela: o prazo vence, a varredura ainda não passou (ela roda
     * em intervalo, não no instante do vencimento), a confirmação chega e é aceita. A reserva
     * deixa de ser {@code PENDING}, a varredura seguinte não a encontra mais — e os assentos
     * ficam vendidos para quem perdeu o prazo. O TTL só vale enquanto nenhuma transição
     * puder atravessá-lo, e é o {@code WHERE} desta consulta que garante isso.
     *
     * <p>O corte usa {@code now()} do próprio banco, e não o relógio da aplicação: com várias
     * instâncias, é o único relógio comum a todas elas — o mesmo critério que a varredura usa.
     *
     * @return 1 quando esta chamada confirmou a reserva, 0 quando o estado ou o prazo a
     *         recusaram — quem chama relê a linha para saber qual dos dois foi
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE reservations
               SET status = 'CONFIRMED',
                   updated_at = now()
             WHERE id = :reservationId
               AND status = 'PENDING'
               AND expires_at > now()
            """, nativeQuery = true)
    int confirmIfPending(@Param("reservationId") UUID reservationId);

    /**
     * Reivindica um lote de reservas vencidas para esta transação.
     *
     * <p>{@code FOR UPDATE} tranca cada linha selecionada até o commit, e
     * {@code SKIP LOCKED} manda o PostgreSQL <strong>pular</strong> as linhas que outra
     * transação já travou em vez de esperar por elas. É o que permite que todas as
     * instâncias da aplicação rodem o mesmo job ao mesmo tempo: cada uma leva um lote
     * disjunto, sem eleição de líder, sem lock distribuído e sem nenhuma instância ociosa.
     *
     * <p>O corte usa {@code now()} do próprio banco — com várias instâncias, é o único
     * relógio comum a todas elas.
     */
    @Query(value = """
            SELECT id FROM reservations
             WHERE status = 'PENDING'
               AND expires_at <= now()
             ORDER BY expires_at
             FOR UPDATE SKIP LOCKED
             LIMIT :batchSize
            """, nativeQuery = true)
    List<UUID> claimExpiredReservations(@Param("batchSize") int batchSize);

    /**
     * Aplica a transição {@code PENDING → EXPIRED} nas reservas do lote reivindicado.
     *
     * <p>A guarda {@code status = 'PENDING'} repete a condição da reivindicação de
     * propósito: é ela que garante que a transição é aplicada uma única vez, mesmo que este
     * método venha a ser chamado sem o lock — a mesma proteção que o cancelamento usa.
     *
     * @return quantas reservas mudaram de estado nesta chamada
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE reservations
               SET status = 'EXPIRED',
                   updated_at = now()
             WHERE id IN (:reservationIds)
               AND status = 'PENDING'
            """, nativeQuery = true)
    int markExpired(@Param("reservationIds") List<UUID> reservationIds);
}
