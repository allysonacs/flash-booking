package com.example.flashbooking.repository;

import com.example.flashbooking.entity.EventInventory;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Acesso a {@link EventInventory}, carregado independentemente dos metadados do evento.
 *
 * <p>As duas operações de inventário são SQL explícito, e não manipulação de entidade. É
 * deliberado: a semântica exata do comando <em>é</em> a solução do problema de concorrência,
 * e escondê-la atrás de um {@code save()} é justamente como se produz oversell.
 */
@Repository
public interface EventInventoryRepository extends JpaRepository<EventInventory, UUID> {

    /**
     * Reserva assentos se — e somente se — couberem na capacidade.
     *
     * <p>Não há leitura prévia: a condição faz parte do próprio {@code UPDATE}, avaliada
     * pelo PostgreSQL sob o lock da linha. Transações concorrentes bloqueiam nesse lock e,
     * ao serem liberadas, reavaliam o predicado contra a versão <strong>já commitada</strong>
     * do contador — e não contra o valor que haviam lido. É isso que torna impossível o
     * lost update, independentemente de quantas instâncias da API existirem.
     *
     * @return 1 quando os assentos foram reservados, 0 quando não havia disponibilidade
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE event_inventory
               SET reserved_count = reserved_count + :quantity,
                   updated_at = now()
             WHERE event_id = :eventId
               AND reserved_count + :quantity <= total_capacity
            """, nativeQuery = true)
    int reserveSeats(@Param("eventId") UUID eventId, @Param("quantity") int quantity);

    /**
     * Devolve assentos ao inventário, nunca abaixo de zero.
     *
     * <p>A condição {@code reserved_count - :quantity >= 0} é a mesma ideia aplicada ao
     * sentido inverso: uma devolução indevida não corrompe o contador, apenas não acontece.
     *
     * @return 1 quando os assentos foram devolvidos, 0 quando a devolução foi recusada
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE event_inventory
               SET reserved_count = reserved_count - :quantity,
                   updated_at = now()
             WHERE event_id = :eventId
               AND reserved_count - :quantity >= 0
            """, nativeQuery = true)
    int releaseSeats(@Param("eventId") UUID eventId, @Param("quantity") int quantity);
}
