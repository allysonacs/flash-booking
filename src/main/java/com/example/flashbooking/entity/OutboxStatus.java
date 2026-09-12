package com.example.flashbooking.entity;

/**
 * Estado de uma mensagem do outbox.
 *
 * <pre>
 *   PENDING ──▶ PUBLISHED   (relay confirmou a gravação no Kafka)
 * </pre>
 *
 * <p>Não existe estado de "falhou". Uma publicação que não deu certo continua
 * {@code PENDING}, com {@code attempts} incrementado — é o que torna a retentativa o
 * comportamento padrão, e não uma exceção que alguém precisa lembrar de tratar.
 */
public enum OutboxStatus {

    /** Gravada na transação do agregado, ainda não confirmada pelo Kafka. */
    PENDING,

    /** O broker confirmou o recebimento; a mensagem não será publicada de novo. */
    PUBLISHED
}
