package com.example.flashbooking.notification;

/**
 * O serviço de notificação recusou o pedido: {@code 4xx} que não seja {@code 429}.
 *
 * <p>É a falha <strong>permanente</strong>: o destinatário entendeu a requisição e disse que
 * ela está errada. Repetir a mesma requisição produz exatamente a mesma recusa, apenas mais
 * vezes — por isso o retry a ignora. E o circuit breaker também a ignora: um payload
 * malformado é defeito <em>nosso</em>, não indisponibilidade <em>deles</em>, e abrir o
 * circuito por causa disso cortaria as notificações legítimas para esconder um bug de
 * contrato.
 */
public class NotificationRejectedException extends NotificationException {

    private final int statusCode;

    public NotificationRejectedException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
