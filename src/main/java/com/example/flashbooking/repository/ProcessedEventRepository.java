package com.example.flashbooking.repository;

import com.example.flashbooking.entity.ProcessedEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Acesso a {@link ProcessedEvent}. */
@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEvent.Key> {

    /**
     * Tenta marcar a mensagem como processada por este grupo.
     *
     * <p>{@code ON CONFLICT DO NOTHING} é a forma correta de perguntar "eu já processei
     * isto?" sob concorrência. A alternativa óbvia — consultar e, se não achar, inserir — é
     * a mesma corrida que a idempotência da reserva evita: entre a consulta e a inserção
     * cabe outro consumidor do mesmo grupo, em outra instância, processando a mesma
     * mensagem. Aqui quem decide é a chave primária.
     *
     * @return 1 quando esta chamada ganhou o direito de processar, 0 quando a mensagem já
     *         havia sido processada
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO processed_events (message_id, consumer_group)
            VALUES (:messageId, :consumerGroup)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int markProcessed(@Param("messageId") UUID messageId,
                      @Param("consumerGroup") String consumerGroup);

    /**
     * Apaga marcas de idempotência mais antigas que o corte.
     *
     * <p>A janela precisa ser <strong>maior que a retenção do tópico</strong>: apagar a
     * marca de uma mensagem que o Kafka ainda pode reentregar é abrir mão da idempotência
     * exatamente no caso em que ela seria usada.
     *
     * @return quantas linhas foram removidas nesta passada
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            DELETE FROM processed_events
             WHERE (message_id, consumer_group) IN (
                   SELECT message_id, consumer_group FROM processed_events
                    WHERE processed_at < now() - CAST(:retention AS interval)
                    LIMIT :batchSize)
            """, nativeQuery = true)
    int deleteProcessedOlderThan(@Param("retention") String retention,
                                 @Param("batchSize") int batchSize);
}
