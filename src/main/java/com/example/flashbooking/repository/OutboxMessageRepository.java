package com.example.flashbooking.repository;

import com.example.flashbooking.entity.OutboxMessage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Acesso a {@link OutboxMessage}. */
@Repository
public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, UUID> {

    /**
     * Reivindica um lote de mensagens pendentes para esta transação.
     *
     * <p>É o mesmo mecanismo da varredura de expiração, pela mesma razão: o relay roda em
     * todas as instâncias, e {@code FOR UPDATE SKIP LOCKED} faz cada uma levar um lote
     * disjunto — sem eleição de líder e sem que duas publiquem a mesma mensagem ao mesmo
     * tempo.
     *
     * <p>{@code ORDER BY created_at} preserva a ordem de produção dentro do lote. Entre
     * lotes concorrentes de instâncias diferentes, a ordem é aproximada — ver o trade-off
     * documentado em ARCHITECTURE §14.
     */
    @Query(value = """
            SELECT id FROM outbox_messages
             WHERE status = 'PENDING'
             ORDER BY created_at
             FOR UPDATE SKIP LOCKED
             LIMIT :batchSize
            """, nativeQuery = true)
    List<UUID> claimPending(@Param("batchSize") int batchSize);

    /**
     * Marca a mensagem como publicada.
     *
     * <p>A guarda {@code status = 'PENDING'} repete o padrão de transição de estado usado no
     * resto do sistema: aplicar duas vezes afeta zero linhas.
     *
     * @return 1 quando esta chamada aplicou a transição
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE outbox_messages
               SET status = 'PUBLISHED',
                   published_at = now(),
                   attempts = attempts + 1,
                   last_error = NULL
             WHERE id = :messageId
               AND status = 'PENDING'
            """, nativeQuery = true)
    int markPublished(@Param("messageId") UUID messageId);

    /**
     * Registra uma tentativa que falhou, mantendo a mensagem pendente.
     *
     * <p>Não existe estado terminal de erro: a mensagem continua na fila e será tentada de
     * novo na próxima passada. É o que garante que um Kafka indisponível <em>atrasa</em> a
     * publicação, mas não perde o evento.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE outbox_messages
               SET attempts = attempts + 1,
                   last_error = :error
             WHERE id = :messageId
               AND status = 'PENDING'
            """, nativeQuery = true)
    int registerFailedAttempt(@Param("messageId") UUID messageId, @Param("error") String error);

    /**
     * Apaga mensagens já publicadas mais antigas que o corte.
     *
     * <p>O outbox é uma fila, não um histórico: sem limpeza ele cresce para sempre, e a
     * tabela que sustenta o caminho crítico da venda vira a maior do banco. O
     * {@code LIMIT} mantém cada passada curta — um {@code DELETE} de milhões de linhas em
     * uma transação só segura locks, incha o WAL e trava a replicação.
     *
     * @return quantas linhas foram removidas nesta passada
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            DELETE FROM outbox_messages
             WHERE id IN (
                   SELECT id FROM outbox_messages
                    WHERE status = 'PUBLISHED'
                      AND published_at < now() - CAST(:retention AS interval)
                    LIMIT :batchSize)
            """, nativeQuery = true)
    int deletePublishedOlderThan(@Param("retention") String retention,
                                 @Param("batchSize") int batchSize);
}
