package com.example.flashbooking.notification;

/**
 * Falha ao entregar uma notificação — sempre de infraestrutura, nunca de domínio.
 *
 * <p>Não estende {@code DomainException} de propósito: não é uma regra de negócio violada e
 * não deve virar {@code 409} para o cliente. Na prática nem chega ao
 * {@code GlobalExceptionHandler}, porque o fallback a absorve antes — a venda já aconteceu,
 * e não é o aviso ao comprador que a invalida.
 *
 * <p>A distinção entre as duas subclasses é o que decide o comportamento do retry e do
 * circuit breaker, e por isso elas existem separadas.
 */
public abstract class NotificationException extends RuntimeException {

    protected NotificationException(String message, Throwable cause) {
        super(message, cause);
    }

    protected NotificationException(String message) {
        super(message, null);
    }
}
