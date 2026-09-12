package com.example.flashbooking.support;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Base dos testes que precisam de um Kafka de verdade.
 *
 * <p>Um broker real, e não um mock: o que se quer provar aqui — particionamento por chave,
 * reentrega depois de uma falha, comportamento de um grupo de consumo — só existe no broker.
 * Um `KafkaTemplate` mockado provaria apenas que o método foi chamado.
 *
 * <p>O container é singleton por JVM, como o PostgreSQL, e as propriedades dinâmicas ficam
 * <strong>nesta classe</strong> de propósito: assim todas as subclasses compartilham a mesma
 * chave de cache de contexto do Spring e, com ela, um único contexto — em vez de subir a
 * aplicação uma vez por classe de teste.
 *
 * <p>O serviço externo de notificação também sobe aqui, porque o consumidor termina nele: é
 * o servidor de mentira que prova que a mensagem foi processada (e que não foi processada
 * duas vezes).
 */
public abstract class AbstractKafkaIntegrationTest extends AbstractIntegrationTest {

    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    protected static final StubNotificationServer NOTIFICATION_SERVICE = StubNotificationServer.start();

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // O consumidor volta a subir (está desligado no perfil de teste) e o tópico é criado.
        registry.add("spring.kafka.listener.auto-startup", () -> true);
        registry.add("flash-booking.events.topic.auto-create", () -> true);
        // O relay continua desligado: os testes o chamam quando querem, o que torna o
        // "antes de publicar" e o "depois de publicar" observáveis em vez de uma corrida.
        registry.add("flash-booking.events.relay.enabled", () -> false);

        registry.add("flash-booking.notification.enabled", () -> true);
        registry.add("flash-booking.notification.base-url", NOTIFICATION_SERVICE::baseUrl);
        registry.add("flash-booking.notification.read-timeout", () -> "500ms");
        registry.add("resilience4j.retry.instances.notification.max-attempts", () -> 1);
    }

    /** Espera até a condição valer, ou falha: consumo assíncrono precisa de um limite. */
    protected static void await(BooleanSupplier condition) {
        await(condition, Duration.ofSeconds(20));
    }

    protected static void await(BooleanSupplier condition, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep();
        }
        throw new AssertionError("A condição esperada não aconteceu em " + timeout);
    }

    /** Dá tempo para que algo que <em>não</em> deveria acontecer teria acontecido. */
    protected static void sleepBriefly(Duration duration) {
        Instant deadline = Instant.now().plus(duration);
        while (Instant.now().isBefore(deadline)) {
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
