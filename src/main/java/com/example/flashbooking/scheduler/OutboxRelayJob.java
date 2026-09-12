package com.example.flashbooking.scheduler;

import com.example.flashbooking.config.EventProperties;
import com.example.flashbooking.messaging.OutboxRelayService;
import com.example.flashbooking.observability.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drena o outbox periodicamente.
 *
 * <p>Mesma forma da varredura de expiração, e pelas mesmas razões: roda em todas as
 * instâncias ao mesmo tempo, e quem impede que duas publiquem a mesma mensagem é o
 * {@code FOR UPDATE SKIP LOCKED} da reivindicação — não uma eleição de líder. O intervalo é
 * curto (1s por padrão) porque ele é a latência que o evento leva para sair do banco.
 */
@Component
@ConditionalOnProperty(name = "flash-booking.events.relay.enabled",
        havingValue = "true", matchIfMissing = true)
public class OutboxRelayJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayJob.class);

    private final OutboxRelayService relayService;
    private final EventProperties.Relay properties;

    public OutboxRelayJob(OutboxRelayService relayService, EventProperties properties) {
        this.relayService = relayService;
        this.properties = properties.relay();
    }

    @Scheduled(fixedDelayString = "${flash-booking.events.relay.interval:PT1S}")
    public void drainOutbox() {
        // Trabalho de fundo também precisa de rastro: sem um id por execução, os logs de
        // várias instâncias drenando ao mesmo tempo viram uma pilha indistinguível.
        CorrelationId.startNew("relay");
        try {
            int totalPublished = 0;

            for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
                int published = relayService.publishPendingBatch(properties.batchSize());
                totalPublished += published;

                if (published < properties.batchSize()) {
                    break;
                }
            }

            if (totalPublished > 0) {
                log.info("Relay do outbox publicou {} mensagem(ns)", totalPublished);
            }
        } finally {
            CorrelationId.clear();
        }
    }
}
