package com.example.flashbooking.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parâmetros de negócio da reserva, externalizados.
 *
 * @param ttl        prazo de validade de uma reserva pendente
 * @param expiration parâmetros da varredura que expira reservas vencidas
 */
@ConfigurationProperties(prefix = "flash-booking.reservation")
public record ReservationProperties(

        @DefaultValue("15m") Duration ttl,
        @DefaultValue Expiration expiration) {

    /**
     * @param batchSize        quantas reservas uma transação reivindica por vez. Lotes
     *                         pequenos mantêm as transações curtas e os locks breves; lotes
     *                         grandes reduzem o número de idas ao banco
     * @param maxBatchesPerRun teto de lotes por execução, para que um acúmulo grande seja
     *                         drenado aos poucos, sem uma execução monopolizar o banco
     */
    public record Expiration(
            @DefaultValue("200") int batchSize,
            @DefaultValue("10") int maxBatchesPerRun) {
    }
}
