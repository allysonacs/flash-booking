package com.example.flashbooking.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.flashbooking.dto.request.CreateEventRequest;
import com.example.flashbooking.dto.request.CreateReservationRequest;
import com.example.flashbooking.entity.ReservationStatus;
import com.example.flashbooking.exception.InsufficientCapacityException;
import com.example.flashbooking.service.EventService;
import com.example.flashbooking.service.ReservationCreationResult;
import com.example.flashbooking.service.ReservationService;
import com.example.flashbooking.support.AbstractIntegrationTest;
import com.example.flashbooking.support.StubNotificationServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Timeout, retry e circuit breaker exercitados contra um serviço externo de verdade — um
 * {@code HttpServer} local que responde devagar, falha e se recupera sob comando.
 *
 * <p>Os números de resiliência são encurtados aqui (espera de 400 ms no estado aberto em vez
 * de 30 s, janela de 6 chamadas em vez de 20) exatamente como em produção: por configuração,
 * sem tocar em uma linha de código. Se algum valor estivesse fixado em classe, este teste
 * não teria como existir — o que é uma boa razão a mais para não fixar.
 */
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class NotificationResilienceIntegrationTest extends AbstractIntegrationTest {

    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);
    private static final Duration SERVER_TOO_SLOW = Duration.ofSeconds(3);
    private static final int MAX_ATTEMPTS = 3;

    private static final StubNotificationServer NOTIFICATION_SERVICE = StubNotificationServer.start();

    @Autowired
    private NotificationGateway gateway;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private EventService eventService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void notificationProperties(DynamicPropertyRegistry registry) {
        registry.add("flash-booking.notification.enabled", () -> true);
        registry.add("flash-booking.notification.base-url", NOTIFICATION_SERVICE::baseUrl);
        registry.add("flash-booking.notification.connect-timeout", () -> "200ms");
        registry.add("flash-booking.notification.read-timeout", () -> READ_TIMEOUT.toMillis() + "ms");

        registry.add("resilience4j.retry.instances.notification.max-attempts", () -> MAX_ATTEMPTS);
        registry.add("resilience4j.retry.instances.notification.wait-duration", () -> "20ms");

        registry.add("resilience4j.circuitbreaker.instances.notification.sliding-window-size", () -> 6);
        registry.add("resilience4j.circuitbreaker.instances.notification.minimum-number-of-calls", () -> 4);
        registry.add("resilience4j.circuitbreaker.instances.notification.wait-duration-in-open-state",
                () -> "400ms");
        registry.add("resilience4j.circuitbreaker.instances.notification"
                + ".permitted-number-of-calls-in-half-open-state", () -> 2);
    }

    @BeforeEach
    void resetExternalServiceAndCircuit() {
        NOTIFICATION_SERVICE.reset();
        circuitBreaker().reset();
    }

    // ---------------------------------------------------------------- timeout

    @Test
    @DisplayName("timeout: o serviço demora, o cliente desiste depressa e a venda não espera")
    void slowServiceIsAbandonedByTheReadTimeout() {
        NOTIFICATION_SERVICE.respondSlowly(SERVER_TOO_SLOW);

        Instant startedAt = Instant.now();
        gateway.send(someNotification());
        Duration elapsed = Duration.between(startedAt, Instant.now());

        // Sem timeout, três tentativas contra um servidor que dorme 3 s levariam 9 s — e a
        // thread ficaria presa esse tempo todo. Com timeout, cada tentativa custa ~300 ms.
        assertThat(elapsed).isLessThan(SERVER_TOO_SLOW);
        assertThat(NOTIFICATION_SERVICE.requestCount()).isEqualTo(MAX_ATTEMPTS);

        // Timeout é falha transitória: contada pelo circuit breaker, absorvida pelo fallback.
        assertThat(failedCalls()).isEqualTo(MAX_ATTEMPTS);
    }

    // ------------------------------------------------------------------ retry

    @Test
    @DisplayName("retry: duas falhas transitórias seguidas de sucesso terminam em sucesso")
    void transientFailuresAreRetriedUntilSuccess() {
        NOTIFICATION_SERVICE.failTimesThenSucceed(2, 503);

        gateway.send(someNotification());

        assertThat(NOTIFICATION_SERVICE.requestCount()).isEqualTo(3);
        assertThat(successfulCalls()).isEqualTo(1);
        assertThat(failedCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("retry: a retentativa carrega a mesma chave de idempotência, e não duplica o aviso")
    void retriesCarryTheSameIdempotencyKey() {
        NOTIFICATION_SERVICE.failTimesThenSucceed(1, 503);
        ReservationNotification notification = someNotification();

        gateway.send(notification);

        assertThat(NOTIFICATION_SERVICE.idempotencyKeys())
                .hasSize(2)
                .containsOnly(notification.reservationId().toString());
    }

    @Test
    @DisplayName("retry: recusa permanente (4xx) não é repetida nem conta para o circuito")
    void permanentRejectionIsNotRetried() {
        NOTIFICATION_SERVICE.alwaysRespond(400);

        gateway.send(someNotification());

        // Uma única requisição: repetir um 400 só produziria outro 400.
        assertThat(NOTIFICATION_SERVICE.requestCount()).isEqualTo(1);

        // E o circuito ignora: contrato quebrado do nosso lado não é indisponibilidade do
        // outro, e abrir o circuito por isso cortaria as notificações que funcionam.
        assertThat(failedCalls()).isZero();
        assertThat(circuitBreaker().getState()).isEqualTo(State.CLOSED);
    }

    // --------------------------------------------------------- circuit breaker

    @Test
    @DisplayName("circuit breaker: falhas sustentadas abrem o circuito e as chamadas param de sair")
    void circuitOpensAndStopsCallingTheFailingService() {
        NOTIFICATION_SERVICE.alwaysRespond(503);

        driveUntilOpen();
        assertThat(circuitBreaker().getState()).isEqualTo(State.OPEN);

        int requestsBefore = NOTIFICATION_SERVICE.requestCount();
        gateway.send(someNotification());

        // Circuito aberto: a chamada falha na hora, dentro da JVM. Nenhuma conexão nova,
        // nenhuma thread parada esperando, nenhuma carga sobre quem já está mal.
        assertThat(NOTIFICATION_SERVICE.requestCount()).isEqualTo(requestsBefore);
        assertThat(circuitBreaker().getMetrics().getNumberOfNotPermittedCalls()).isPositive();
    }

    @Test
    @DisplayName("circuit breaker: aberto → half-open → fechado quando o serviço volta")
    void circuitRecoversThroughHalfOpen() {
        NOTIFICATION_SERVICE.alwaysRespond(503);
        driveUntilOpen();

        // A transição para HALF_OPEN é automática: não depende de alguém chamar, o que
        // importa aqui porque o envio é assíncrono e pode não haver tráfego nenhum.
        NOTIFICATION_SERVICE.reset();
        await(() -> circuitBreaker().getState() == State.HALF_OPEN);

        // Em HALF_OPEN passa um número limitado de chamadas de prova. Todas funcionando, o
        // circuito fecha e o tráfego normal volta.
        gateway.send(someNotification());
        gateway.send(someNotification());

        assertThat(circuitBreaker().getState()).isEqualTo(State.CLOSED);
        assertThat(NOTIFICATION_SERVICE.requestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("circuit breaker: se a prova falha em half-open, o circuito volta a abrir")
    void failedProbeReopensTheCircuit() {
        NOTIFICATION_SERVICE.alwaysRespond(503);
        driveUntilOpen();

        await(() -> circuitBreaker().getState() == State.HALF_OPEN);
        gateway.send(someNotification());

        // O serviço continua fora; a prova falha e o circuito reabre em vez de despejar
        // tráfego em cima dele.
        assertThat(circuitBreaker().getState()).isEqualTo(State.OPEN);
    }

    // --------------------------------------------------------------- fallback

    @Test
    @DisplayName("fallback: nenhuma falha da notificação escapa para quem chamou")
    void noFailureEverEscapesTheGateway() {
        NOTIFICATION_SERVICE.alwaysRespond(500);

        // Três formas diferentes de falhar, nenhuma com direito a derrubar a venda.
        gateway.send(someNotification());
        NOTIFICATION_SERVICE.alwaysRespond(422);
        gateway.send(someNotification());
        NOTIFICATION_SERVICE.respondSlowly(SERVER_TOO_SLOW);
        gateway.send(someNotification());

        assertThat(NOTIFICATION_SERVICE.requestCount()).isPositive();
    }

    // ------------------------------------------- a venda, que não depende de nada disso

    @Test
    @DisplayName("a venda não fala com serviço externo nenhum: ela grava o evento no outbox")
    void saleOnlyWritesToTheOutbox() {
        NOTIFICATION_SERVICE.alwaysRespond(500);
        UUID eventId = eventService.create(new CreateEventRequest("Evento notificado", 10)).id();

        ReservationCreationResult result =
                reservationService.create(eventId, new CreateReservationRequest(3), null);

        // A venda está feita, e o caminho dela não tocou a rede: a notificação virou
        // consequência de um evento publicado depois do commit, e não uma chamada embutida
        // na transação. Um serviço externo fora do ar não tem como afetar isto.
        assertThat(result.replayed()).isFalse();
        assertThat(result.reservation().status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(NOTIFICATION_SERVICE.requestCount()).isZero();

        assertThat(pendingOutboxMessages(result.reservation().id())).isEqualTo(1);
    }

    @Test
    @DisplayName("erro de negócio não é mascarado pela resiliência: esgotado continua sendo esgotado")
    void businessErrorsAreNeverSwallowed() {
        NOTIFICATION_SERVICE.alwaysRespond(500);
        UUID eventId = eventService.create(new CreateEventRequest("Evento pequeno", 2)).id();

        // O fallback existe para a borda externa, e só para ela. Uma regra de negócio
        // violada continua chegando inteira a quem chamou — resiliência que engole erro de
        // domínio é como se vende um ingresso que não existe.
        assertThatThrownBy(() -> reservationService.create(
                eventId, new CreateReservationRequest(5), null))
                .isInstanceOf(InsufficientCapacityException.class);

        // E nada foi notificado, porque nada foi vendido.
        assertThat(NOTIFICATION_SERVICE.requestCount()).isZero();
    }

    // ------------------------------------------------------------------ apoio

    /**
     * Força chamadas suficientes para o circuito decidir. Cada envio é uma rajada de
     * {@code MAX_ATTEMPTS} tentativas, e cada tentativa é uma chamada registrada — é assim
     * que o retry e o circuit breaker se compõem na ordem padrão do Resilience4j.
     */
    private void driveUntilOpen() {
        for (int i = 0; i < 5 && circuitBreaker().getState() != State.OPEN; i++) {
            gateway.send(someNotification());
        }
    }

    private Integer pendingOutboxMessages(UUID reservationId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM outbox_messages
                 WHERE aggregate_id = ? AND event_type = 'ReservationCreated' AND status = 'PENDING'
                """, Integer.class, reservationId);
    }

    private static ReservationNotification someNotification() {
        return new ReservationNotification(UUID.randomUUID(), UUID.randomUUID(), 1,
                Instant.now().plusSeconds(900));
    }

    private CircuitBreaker circuitBreaker() {
        return circuitBreakerRegistry.circuitBreaker(NotificationGateway.INSTANCE);
    }

    private int failedCalls() {
        return circuitBreaker().getMetrics().getNumberOfFailedCalls();
    }

    private int successfulCalls() {
        return circuitBreaker().getMetrics().getNumberOfSuccessfulCalls();
    }

    private static void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        throw new AssertionError("A condição esperada não aconteceu em 5 s");
    }
}
