package com.example.flashbooking.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publica o tamanho da fila do outbox como métrica.
 *
 * <p>É o indicador que separa "o Kafka caiu" de "está tudo bem": a venda continua
 * funcionando nos dois casos, e sem esta métrica a diferença só aparece quando alguém
 * reclama que não recebeu a notificação. Uma fila que cresce de forma monótona é alarme.
 *
 * <p>O valor é amostrado por um agendamento próprio, e não calculado no momento do
 * <em>scrape</em>: assim a frequência da consulta depende da configuração da aplicação, e
 * não de quantos coletores estão apontados para ela. A consulta usa o índice parcial de
 * pendentes, então conta apenas a fila viva.
 */
@Component
@ConditionalOnProperty(name = "flash-booking.observability.outbox-gauge.enabled",
        havingValue = "true", matchIfMissing = true)
public class OutboxBacklogMetrics implements MeterBinder {

    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong pending = new AtomicLong();

    public OutboxBacklogMetrics(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("flashbooking.outbox.pending", pending, AtomicLong::doubleValue)
                .description("Eventos aguardando publicação no Kafka")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${flash-booking.observability.outbox-gauge.interval:PT10S}")
    public void sample() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_messages WHERE status = 'PENDING'", Long.class);
        pending.set(count == null ? 0 : count);
    }
}
