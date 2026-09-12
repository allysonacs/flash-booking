package com.example.flashbooking.scheduler;

import com.example.flashbooking.messaging.MessagingRetentionService;
import com.example.flashbooking.observability.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Mantém as tabelas de mensageria do tamanho do trabalho, e não do tempo de vida do sistema.
 *
 * <p>Sem isto, `outbox_messages` e `processed_events` crescem indefinidamente dentro do
 * mesmo banco que decide as vendas: índices maiores, autovacuum mais caro, backup mais
 * lento. É uma dívida silenciosa — não quebra nada hoje, e é impossível de pagar com pressa
 * depois.
 *
 * <p>Roda em todas as instâncias, como os demais jobs. Não há disputa a resolver: apagar
 * duas vezes a mesma linha já apagada é inofensivo, e o {@code LIMIT} de cada passada
 * mantém as transações curtas.
 */
@Component
@ConditionalOnProperty(name = "flash-booking.events.retention.enabled",
        havingValue = "true", matchIfMissing = true)
public class MessagingRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(MessagingRetentionJob.class);

    private final MessagingRetentionService retentionService;

    public MessagingRetentionJob(MessagingRetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @Scheduled(fixedDelayString = "${flash-booking.events.retention.interval:PT1H}")
    public void purgeOldRows() {
        CorrelationId.startNew("retention");
        try {
            int removed = retentionService.purge();
            if (removed > 0) {
                log.info("Retenção removeu {} linha(s) das tabelas de mensageria", removed);
            }
        } finally {
            CorrelationId.clear();
        }
    }
}
