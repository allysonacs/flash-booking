package com.example.flashbooking.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

/**
 * As métricas de <strong>negócio</strong> — as que o Micrometer não tem como adivinhar.
 *
 * <p>Latência por rota, taxa de erro HTTP, uso do pool de conexões, GC, estados do circuit
 * breaker: tudo isso já vem de graça do Actuator, do Hikari e do Resilience4j, e não é
 * reimplementado aqui. O que falta a uma flash sale é o que só o domínio sabe:
 *
 * <ul>
 *   <li><strong>quantas reservas foram recusadas por falta de assento</strong> — a diferença
 *       entre "o sistema está lento" e "o evento esgotou" é invisível nas métricas técnicas,
 *       porque as duas aparecem como {@code 409};
 *   <li><strong>quantas foram confirmadas</strong> — contra as criadas, é a taxa de
 *       conversão da venda, que nenhuma métrica técnica enxerga;
 *   <li><strong>quantas expiraram</strong> — indica carrinho abandonado, e um salto súbito
 *       costuma significar que algo na confirmação quebrou;
 *   <li><strong>o tamanho da fila do outbox</strong> — é o alarme de que a integração parou
 *       sem que a venda tenha parado, que é justamente o caso em que ninguém percebe.
 * </ul>
 *
 * <p>Poucas métricas, cada uma com uma pergunta operacional associada. Métrica que ninguém
 * olha é custo de cardinalidade e ruído de painel.
 */
@Component
public class ReservationMetrics {

    private static final String CREATED = "flashbooking.reservations.created";
    private static final String CONFIRMED = "flashbooking.reservations.confirmed";
    private static final String REJECTED = "flashbooking.reservations.rejected";
    private static final String CANCELLED = "flashbooking.reservations.cancelled";
    private static final String EXPIRED = "flashbooking.reservations.expired";
    private static final String SEATS_RELEASED = "flashbooking.seats.released";
    private static final String OUTBOX_PUBLISHED = "flashbooking.outbox.published";

    private final MeterRegistry registry;

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void reservationCreated(int seats) {
        registry.counter(CREATED).increment();
        registry.counter("flashbooking.seats.reserved").increment(seats);
    }

    /**
     * @param reason motivo da recusa. É um conjunto <strong>fechado</strong> de valores
     *               vindos do código, nunca texto de entrada do usuário: tag de
     *               cardinalidade aberta é o jeito clássico de derrubar um sistema de
     *               métricas com o próprio tráfego
     */
    public void reservationRejected(String reason) {
        Counter.builder(REJECTED).tags(Tags.of("reason", reason)).register(registry).increment();
    }

    /**
     * Confirmações. Contra {@code reservations.created} e {@code reservations.expired}, é o
     * que responde à pergunta de negócio da flash sale: <em>quantas das reservas viram
     * venda?</em> Uma queda dessa razão, com criação estável, é carrinho abandonado — ou um
     * defeito no caminho da confirmação, que nenhuma métrica técnica acusaria.
     */
    public void reservationConfirmed(int seats) {
        registry.counter(CONFIRMED).increment();
        registry.counter("flashbooking.seats.confirmed").increment(seats);
    }

    public void reservationCancelled(int seats) {
        registry.counter(CANCELLED).increment();
        registry.counter(SEATS_RELEASED, "cause", "cancellation").increment(seats);
    }

    public void reservationsExpired(int count, int seats) {
        registry.counter(EXPIRED).increment(count);
        registry.counter(SEATS_RELEASED, "cause", "expiration").increment(seats);
    }

    public void outboxPublished(int count) {
        registry.counter(OUTBOX_PUBLISHED).increment(count);
    }
}
