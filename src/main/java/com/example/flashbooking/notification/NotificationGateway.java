package com.example.flashbooking.notification;

import com.example.flashbooking.config.NotificationProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * A única dependência externa do sistema — e, por isso, o único lugar com resiliência.
 *
 * <p>Nada aqui protege o PostgreSQL: sem banco não existe venda, e um circuit breaker na
 * frente dele apenas trocaria um erro explícito por uma resposta inventada. Resiliência se
 * aplica onde há uma dependência que pode degradar <strong>sem invalidar a operação
 * principal</strong>, e a notificação é exatamente isso: a reserva já está commitada quando
 * este código roda.
 *
 * <p><strong>As três proteções, e o que cada uma resolve:</strong>
 *
 * <ol>
 *   <li><strong>Timeout</strong> (no cliente HTTP, ver {@code NotificationClientConfig}) —
 *       limita quanto tempo uma chamada pode ficar pendurada. É a proteção mais básica e a
 *       mais importante: sem ela, as outras duas nunca chegam a agir, porque a thread não
 *       volta.
 *   <li><strong>Retry</strong> — cobre a falha transitória: um pacote perdido, um pod
 *       reiniciando, um {@code 503} momentâneo.
 *   <li><strong>Circuit breaker</strong> — cobre a falha <em>persistente</em>: se o serviço
 *       está fora, insistir só gasta threads e adia o inevitável. Com o circuito aberto, a
 *       chamada falha imediatamente, sem sair da JVM.
 * </ol>
 *
 * <p><strong>Ordem dos aspectos, e onde o fallback precisa ficar:</strong> o padrão do
 * Resilience4j é {@code Retry(CircuitBreaker(chamada))} — o retry é o mais externo. Cada
 * tentativa é registrada individualmente pelo circuit breaker, que assim enxerga a taxa real
 * de falhas; e quando o circuito abre, as tentativas seguintes falham na hora, com
 * {@link CallNotPermittedException}, que não é repetida.
 *
 * <p>Daí o {@code fallbackMethod} estar declarado no {@code @Retry}, e não no
 * {@code @CircuitBreaker}: o fallback trata a exceção e devolve normalmente, então um
 * fallback no aspecto <em>interno</em> faria o aspecto externo enxergar sucesso — o retry
 * nunca repetiria nada, e o circuit breaker registraria chamadas bem-sucedidas que na
 * verdade falharam. O fallback pertence à borda de fora: primeiro insiste, depois protege,
 * e só então desiste.
 *
 * <p><strong>Fallback:</strong> qualquer que seja a falha, a notificação é descartada com
 * log. O que <em>não</em> acontece é a falha subir: a reserva foi criada, os assentos estão
 * comprometidos e o cliente já recebeu {@code 201}. Deixar uma falha de aviso derrubar essa
 * resposta seria inventar um erro que não existe. Isso vale apenas para esta borda — erro de
 * negócio continua chegando ao cliente como sempre.
 */
@Component
public class NotificationGateway {

    private static final Logger log = LoggerFactory.getLogger(NotificationGateway.class);

    /** Nome da instância configurada em {@code resilience4j.*.instances.notification}. */
    public static final String INSTANCE = "notification";

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final RestClient restClient;
    private final NotificationProperties properties;

    public NotificationGateway(RestClient notificationRestClient, NotificationProperties properties) {
        this.restClient = notificationRestClient;
        this.properties = properties;
    }

    /**
     * Entrega a notificação ao serviço externo.
     *
     * <p>A requisição carrega {@code Idempotency-Key} com o id da reserva. É o que autoriza o
     * retry: repetir uma entrega que talvez já tenha chegado só é seguro quando o
     * destinatário consegue reconhecer a repetição.
     */
    @Retry(name = INSTANCE, fallbackMethod = "dropNotification")
    @CircuitBreaker(name = INSTANCE)
    public void send(ReservationNotification notification) {
        if (!properties.enabled()) {
            log.debug("Notificações desligadas; reserva {} não será notificada",
                    notification.reservationId());
            return;
        }

        try {
            restClient.post()
                    .uri(properties.path())
                    .header(IDEMPOTENCY_KEY_HEADER, notification.reservationId().toString())
                    .body(notification)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw translate(response.getStatusCode());
                    })
                    .toBodilessEntity();

            log.debug("Reserva {} notificada", notification.reservationId());

        } catch (ResourceAccessException networkFailure) {
            // Timeout de conexão, timeout de leitura ou conexão recusada: o serviço não
            // respondeu. É a falha transitória por excelência — e a que o timeout do cliente
            // transforma em erro rápido em vez de thread pendurada.
            throw new NotificationUnavailableException(
                    "Serviço de notificação não respondeu: " + networkFailure.getMessage(),
                    networkFailure);
        }
    }

    /**
     * Traduz o status HTTP em "vale a pena tentar de novo?" — a única pergunta que importa
     * para decidir entre retry e desistência.
     */
    private NotificationException translate(HttpStatusCode status) {
        boolean transientFailure = status.is5xxServerError() || status.value() == 429;

        return transientFailure
                ? new NotificationUnavailableException(
                        "Serviço de notificação respondeu " + status.value())
                : new NotificationRejectedException(status.value(),
                        "Serviço de notificação recusou a notificação com " + status.value());
    }

    /**
     * Fallback do circuito aberto: a chamada nem chega a sair da JVM.
     *
     * <p>Separado do fallback geral de propósito — este não é um erro do serviço externo, é
     * a decisão local de <em>não chamar</em>. Confundir os dois no log é o que faz alguém
     * procurar um incidente de rede que não existe.
     */
    @SuppressWarnings("unused") // invocado pelo Resilience4j por assinatura
    private void dropNotification(ReservationNotification notification,
                                  CallNotPermittedException circuitOpen) {
        log.warn("Circuito '{}' aberto: notificação da reserva {} descartada sem tentativa de rede",
                INSTANCE, notification.reservationId());
    }

    /**
     * Fallback das falhas de entrega, depois de esgotadas as tentativas.
     *
     * <p>Nível de log diferente por tipo, porque a ação de quem está de plantão é diferente:
     * indisponibilidade é problema do outro lado e costuma passar; recusa é contrato
     * quebrado do nosso lado e não passa sozinha.
     */
    @SuppressWarnings("unused") // invocado pelo Resilience4j por assinatura
    private void dropNotification(ReservationNotification notification,
                                  NotificationException failure) {
        if (failure instanceof NotificationRejectedException rejected) {
            log.error("Notificação da reserva {} recusada com {} — payload ou contrato inválido",
                    notification.reservationId(), rejected.getStatusCode());
            return;
        }
        log.warn("Notificação da reserva {} descartada após as tentativas: {}",
                notification.reservationId(), failure.getMessage());
    }
}
