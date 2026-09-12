package com.example.flashbooking.notification;

/**
 * O serviço de notificação não respondeu, ou respondeu que não conseguiu processar agora.
 *
 * <p>É a falha <strong>transitória</strong>: timeout de conexão ou de leitura, conexão
 * recusada, {@code 5xx}, {@code 429}. Tentar de novo daqui a pouco tem chance real de
 * funcionar, então é esta — e apenas esta — que o retry repete e que o circuit breaker
 * conta como falha.
 */
public class NotificationUnavailableException extends NotificationException {

    public NotificationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public NotificationUnavailableException(String message) {
        super(message);
    }
}
