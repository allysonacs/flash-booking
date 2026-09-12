package com.example.flashbooking.messaging;

import com.example.flashbooking.config.EventProperties;
import com.example.flashbooking.repository.OutboxMessageRepository;
import com.example.flashbooking.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Apaga o que já cumpriu seu papel: mensagens publicadas e marcas de idempotência velhas.
 *
 * <p>As duas janelas são diferentes de propósito. Uma mensagem publicada já não serve a
 * ninguém depois que o consumidor a processou — sobra como evidência. Já a marca de
 * idempotência precisa sobreviver a <strong>toda</strong> a janela em que o Kafka ainda
 * poderia reentregar a mensagem: apagá-la antes disso reabre exatamente a duplicata que ela
 * existia para impedir.
 */
@Service
public class MessagingRetentionService {

    private static final Logger log = LoggerFactory.getLogger(MessagingRetentionService.class);

    private final OutboxMessageRepository outboxRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final EventProperties.Retention properties;

    public MessagingRetentionService(OutboxMessageRepository outboxRepository,
                                     ProcessedEventRepository processedEventRepository,
                                     EventProperties properties) {
        this.outboxRepository = outboxRepository;
        this.processedEventRepository = processedEventRepository;
        this.properties = properties.retention();
    }

    /**
     * Executa uma passada de limpeza.
     *
     * <p>Cada passada é limitada a {@code batchSize} linhas por tabela: um {@code DELETE}
     * gigante segura locks, incha o WAL e atrasa a replicação — numa flash sale, a limpeza
     * não pode ser o que derruba a venda. O que sobrar fica para a passada seguinte.
     *
     * @return quantas linhas foram removidas ao todo
     */
    @Transactional
    public int purge() {
        int outbox = outboxRepository.deletePublishedOlderThan(
                properties.publishedFor(), properties.batchSize());
        int processed = processedEventRepository.deleteProcessedOlderThan(
                properties.processedEventsFor(), properties.batchSize());

        if (outbox > 0 || processed > 0) {
            log.debug("Retenção: {} mensagem(ns) e {} marca(s) removidas", outbox, processed);
        }
        return outbox + processed;
    }
}
