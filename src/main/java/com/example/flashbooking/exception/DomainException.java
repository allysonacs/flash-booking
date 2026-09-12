package com.example.flashbooking.exception;

/**
 * Base das falhas previsíveis de domínio — aquelas em que o sistema funcionou como deveria
 * e a operação, ainda assim, não pode ser concluída.
 *
 * <p>Carrega um {@code errorCode} estável, pensado para ser consumido por máquina: o texto
 * da mensagem pode mudar, ser traduzido ou ganhar detalhe, mas o código é contrato. A
 * tradução de exceção para status HTTP é responsabilidade do
 * {@link GlobalExceptionHandler} — o domínio não conhece HTTP.
 */
public abstract class DomainException extends RuntimeException {

    private final String errorCode;

    protected DomainException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
