package com.example.flashbooking.messaging;

import com.example.flashbooking.entity.OutboxMessage;
import com.example.flashbooking.entity.Reservation;
import com.example.flashbooking.observability.CorrelationId;
import com.example.flashbooking.repository.OutboxMessageRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Grava o evento de integração na mesma transação do agregado.
 *
 * <p><strong>O risco que isto existe para eliminar.</strong> A versão ingênua é esta:
 *
 * <pre>{@code
 * reservationRepository.save(reservation);   // ① commit no PostgreSQL
 * kafkaTemplate.send(topic, event);          // ② publicação no Kafka
 * }</pre>
 *
 * <p>São duas escritas em dois sistemas que não compartilham transação, e as duas ordens
 * possíveis falham de formas diferentes:
 *
 * <ul>
 *   <li><strong>Publicar depois do commit</strong> — se o processo morre entre ① e ②, ou se
 *       o Kafka está fora, a reserva existe e o evento <em>nunca</em> existirá. O consumidor
 *       jamais saberá da venda, e nenhuma retentativa vai acontecer, porque não sobrou
 *       registro de que havia algo a publicar.
 *   <li><strong>Publicar dentro da transação</strong> — se o commit falha depois do envio, o
 *       evento anuncia uma reserva que o rollback desfez. O consumidor reage a um fato que
 *       não aconteceu: notifica uma compra inexistente, alimenta um relatório errado.
 * </ul>
 *
 * <p>Não há ordem correta, porque o problema não é de ordem: é a ausência de uma transação
 * comum. Two-phase commit resolveria, ao custo de um coordenador e de travar o banco pela
 * disponibilidade do broker — inaceitável em uma flash sale.
 *
 * <p><strong>A solução.</strong> Escrever o evento onde já existe transação: no próprio
 * PostgreSQL. Reserva e evento entram no mesmo commit, e um relay assíncrono leva do banco
 * ao Kafka. A entrega passa a ser <em>at-least-once</em> — publicar e marcar como publicado
 * também não são atômicos —, e é por isso que o consumidor é idempotente. A troca é
 * deliberada: perder uma mensagem é irreparável, recebê-la duas vezes é tratável.
 */
@Component
public class OutboxRecorder {

    private static final String AGGREGATE_TYPE = "RESERVATION";

    private final OutboxMessageRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxRecorder(OutboxMessageRepository outboxRepository,
                          ObjectMapper objectMapper,
                          Clock clock) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Enfileira o {@link ReservationCreated} correspondente à reserva.
     *
     * <p>Chamado de dentro da transação que cria a reserva — o commit que grava uma grava a
     * outra. Se a reserva for desfeita por falta de assento ou por chave de idempotência
     * repetida, o evento desaparece junto: não sobra anúncio de uma venda que não houve.
     */
    public void recordReservationCreated(Reservation reservation) {
        UUID messageId = UUID.randomUUID();
        ReservationCreated event =
                ReservationCreated.from(reservation, messageId, Instant.now(clock));

        OutboxMessage message = new OutboxMessage(
                messageId,
                AGGREGATE_TYPE,
                reservation.getId(),
                // Chave de partição: o show. Reservas do mesmo show caem na mesma partição e
                // são entregues na ordem em que foram publicadas.
                reservation.getEventId().toString(),
                ReservationCreated.TYPE,
                serialize(event),
                // O rastro da requisição segue com o evento: é o que liga, no log, a venda
                // ao efeito que ela provoca três saltos depois.
                CorrelationId.current());

        // O id da linha e o messageId do payload são o mesmo valor de propósito: é assim
        // que o consumidor consegue deduplicar a mensagem que recebeu, e é assim que uma
        // mensagem no Kafka pode ser rastreada até a linha que a originou.
        outboxRepository.save(message);
    }

    private String serialize(ReservationCreated event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JacksonException failure) {
            // Serialização quebrada é defeito de programação, não falha de runtime a
            // tolerar: melhor derrubar a transação inteira do que gravar um evento que
            // ninguém conseguirá ler.
            throw new IllegalStateException("Não foi possível serializar " + event, failure);
        }
    }
}
