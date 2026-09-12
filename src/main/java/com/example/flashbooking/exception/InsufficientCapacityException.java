package com.example.flashbooking.exception;

import java.util.UUID;

/**
 * Não há assentos suficientes para atender à reserva.
 *
 * <p>É o resultado esperado de uma flash sale disputada, e não um defeito: significa apenas
 * que esta requisição perdeu a corrida. A mensagem não informa quantos assentos restam —
 * qualquer número seria obsoleto no instante em que chegasse ao cliente.
 */
public class InsufficientCapacityException extends DomainException {

    public static final String ERROR_CODE = "INSUFFICIENT_CAPACITY";

    public InsufficientCapacityException(UUID eventId, int quantity) {
        super(ERROR_CODE, "Não há %d assento(s) disponível(is) para o evento %s"
                .formatted(quantity, eventId));
    }
}
