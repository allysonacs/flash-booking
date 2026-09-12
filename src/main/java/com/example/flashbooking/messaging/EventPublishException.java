package com.example.flashbooking.messaging;

/**
 * O broker não confirmou a publicação.
 *
 * <p>Nunca chega à API: o relay a captura, registra a tentativa e deixa a mensagem pendente.
 * Kafka fora do ar atrasa a integração — não impede a venda, que já está commitada.
 */
public class EventPublishException extends RuntimeException {

    public EventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
