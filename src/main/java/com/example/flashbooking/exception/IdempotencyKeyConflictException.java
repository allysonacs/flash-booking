package com.example.flashbooking.exception;

/**
 * A mesma chave de idempotência foi reutilizada com um payload diferente.
 *
 * <p>Repetir a chave com o mesmo payload é retentativa, e é atendida com a resposta
 * original. Repetir com payload diferente é outra intenção reusando a mesma chave — atender
 * silenciosamente devolveria ao cliente o resultado de uma operação que ele não pediu.
 */
public class IdempotencyKeyConflictException extends DomainException {

    public static final String ERROR_CODE = "IDEMPOTENCY_KEY_CONFLICT";

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super(ERROR_CODE, "A chave de idempotência '%s' já foi usada com um payload diferente"
                .formatted(idempotencyKey));
    }
}
