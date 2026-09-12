package com.example.flashbooking.scheduler;

import com.example.flashbooking.config.ReservationProperties;
import com.example.flashbooking.observability.CorrelationId;
import com.example.flashbooking.service.ReservationExpirationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Varredura periódica das reservas vencidas.
 *
 * <p>Roda em <strong>todas</strong> as instâncias, ao mesmo tempo, de propósito. Quem impede
 * que duas processem a mesma reserva não é uma eleição de líder nem um lock distribuído — é
 * o {@code FOR UPDATE SKIP LOCKED} da reivindicação, que faz cada instância levar um lote
 * disjunto. Não há instância ociosa, não há ponto único de falha do job e não há
 * dependência nova para operar.
 *
 * <p>O trabalho é dividido em lotes pequenos, cada um em sua própria transação: transações
 * curtas soltam os locks depressa e não competem com as vendas em andamento. Uma execução
 * drena no máximo {@code maxBatchesPerRun} lotes; o que sobrar fica para a próxima, que vem
 * segundos depois.
 *
 * <p>{@code fixedDelay} e não {@code fixedRate}: o intervalo é contado a partir do fim da
 * execução anterior, então uma varredura lenta não acumula execuções sobrepostas.
 */
@Component
@ConditionalOnProperty(name = "flash-booking.reservation.expiration.enabled",
        havingValue = "true", matchIfMissing = true)
public class ReservationExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirationJob.class);

    private final ReservationExpirationService expirationService;
    private final ReservationProperties.Expiration properties;

    public ReservationExpirationJob(ReservationExpirationService expirationService,
                                    ReservationProperties properties) {
        this.expirationService = expirationService;
        this.properties = properties.expiration();
    }

    @Scheduled(fixedDelayString = "${flash-booking.reservation.expiration.interval:PT30S}")
    public void expireDueReservations() {
        CorrelationId.startNew("expiration");
        try {
            int totalExpired = 0;

            for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
                int expired = expirationService.expireDueReservations(properties.batchSize());
                totalExpired += expired;

                if (expired < properties.batchSize()) {
                    break;
                }
            }

            if (totalExpired > 0) {
                log.info("Varredura de expiração concluída: {} reserva(s) expirada(s)", totalExpired);
            }
        } finally {
            CorrelationId.clear();
        }
    }
}
