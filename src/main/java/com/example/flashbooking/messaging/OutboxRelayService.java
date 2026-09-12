package com.example.flashbooking.messaging;

import com.example.flashbooking.entity.OutboxMessage;
import com.example.flashbooking.observability.ReservationMetrics;
import com.example.flashbooking.repository.OutboxMessageRepository;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leva as mensagens do outbox para o Kafka.
 *
 * <p>O relay é a metade assíncrona do padrão: a transação da venda apenas <em>enfileira</em>,
 * e é aqui que a publicação de fato acontece — fora do caminho quente, sem que a venda
 * dependa da disponibilidade do broker.
 *
 * <p><strong>Uma falha não perde nada.</strong> Se o broker não confirma, a mensagem
 * continua {@code PENDING} com {@code attempts} incrementado, e a próxima passada tenta de
 * novo. A retentativa é o comportamento padrão, não um caminho especial: só sai da fila o
 * que o Kafka confirmou.
 *
 * <p><strong>O lote para na primeira falha.</strong> Cada tentativa espera até o
 * {@code publish-timeout}, e todas acontecem dentro da mesma transação de banco. Insistir
 * nas mensagens seguintes com o broker fora transformaria um lote de 100 em uma transação de
 * minutos, segurando conexão e locks — e atrasando a própria retentativa. Parar cedo mantém
 * cada passada curta e o custo de um broker indisponível limitado a um timeout por passada.
 *
 * <p><strong>Por que pode haver duplicata.</strong> Publicar no Kafka e marcar como
 * publicado no PostgreSQL são duas escritas em dois sistemas — o mesmo problema que o outbox
 * resolveu para a venda, agora deslocado para cá, onde é tratável. Se o processo morre entre
 * a confirmação do broker e o commit da marcação, a mensagem volta a ser publicada. Daí a
 * entrega ser <em>at-least-once</em>, e o consumidor ser idempotente por construção.
 * Deslocar o problema para o lado em que a duplicata é barata é exatamente o objetivo.
 */
@Service
public class OutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);

    private final OutboxMessageRepository outboxRepository;
    private final KafkaEventPublisher publisher;
    private final ReservationMetrics metrics;

    public OutboxRelayService(OutboxMessageRepository outboxRepository,
                              KafkaEventPublisher publisher,
                              ReservationMetrics metrics) {
        this.outboxRepository = outboxRepository;
        this.publisher = publisher;
        this.metrics = metrics;
    }

    /**
     * Publica até {@code batchSize} mensagens pendentes.
     *
     * @return quantas foram confirmadas pelo broker nesta passada
     */
    @Transactional
    public int publishPendingBatch(int batchSize) {
        List<UUID> claimed = outboxRepository.claimPending(batchSize);
        if (claimed.isEmpty()) {
            return 0;
        }

        // findAllById não preserva a ordem da reivindicação; reordenar por created_at mantém
        // as mensagens de um mesmo show saindo na ordem em que foram produzidas.
        List<OutboxMessage> messages = outboxRepository.findAllById(claimed).stream()
                .sorted(Comparator.comparing(OutboxMessage::getCreatedAt))
                .toList();

        int published = 0;
        for (OutboxMessage message : messages) {
            if (!publishOne(message)) {
                // Interromper o lote na PRIMEIRA falha é deliberado. A causa quase sempre é
                // o broker indisponível, e nesse caso as mensagens seguintes vão falhar
                // igual — cada uma esperando o publish-timeout inteiro, tudo isso dentro
                // desta transação, com as linhas reivindicadas travadas. Um lote de 100 com
                // o Kafka fora seria uma transação de minutos: conexão ocupada, autovacuum
                // bloqueado e a retentativa adiada exatamente quando ela mais importa.
                // O que sobrou continua PENDING, intacto, e sai na próxima passada.
                log.warn("Lote interrompido na mensagem {}: as demais ficam para a próxima passada",
                        message.getId());
                break;
            }
            published++;
        }
        metrics.outboxPublished(published);
        return published;
    }

    /** @return {@code true} se o broker confirmou a mensagem */
    private boolean publishOne(OutboxMessage message) {
        try {
            publisher.publish(message);
            return outboxRepository.markPublished(message.getId()) == 1;

        } catch (EventPublishException failure) {
            log.warn("Falha ao publicar a mensagem {} (tentativa {}): {}",
                    message.getId(), message.getAttempts() + 1, failure.getMessage());
            outboxRepository.registerFailedAttempt(message.getId(), describe(failure));
            return false;
        }
    }

    /** Guarda o motivo em texto: é o que alguém de plantão lê antes de olhar o log. */
    private static String describe(EventPublishException failure) {
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        return "%s (%s: %s)".formatted(
                failure.getMessage(), cause.getClass().getSimpleName(), cause.getMessage());
    }
}
