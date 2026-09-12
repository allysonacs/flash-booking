package com.example.flashbooking.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Um serviço de notificação de mentira, mas de verdade: HTTP real, socket real, latência
 * real.
 *
 * <p>Essa escolha é o que dá sentido aos testes de resiliência. Um mock que lança
 * {@code SocketTimeoutException} prova apenas que o {@code catch} funciona; aqui o servidor
 * de fato demora a responder, e o timeout precisa ser o do cliente HTTP para o teste passar.
 * O mesmo vale para o retry — quem conta as tentativas é o servidor que as recebeu.
 *
 * <p>Usa o {@code HttpServer} do próprio JDK: não é preciso trazer WireMock para subir um
 * endpoint que devolve um status e, às vezes, dorme antes.
 */
public final class StubNotificationServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<Behaviour> behaviour = new AtomicReference<>(Behaviour.ok());
    private final List<String> idempotencyKeys = Collections.synchronizedList(new ArrayList<>());

    private StubNotificationServer(HttpServer server) {
        this.server = server;
    }

    public static StubNotificationServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StubNotificationServer stub = new StubNotificationServer(server);
            server.createContext("/", stub::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return stub;
        } catch (IOException failure) {
            throw new UncheckedIOException("Não foi possível subir o serviço de notificação de teste", failure);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Quantas requisições chegaram de fato — a evidência de retry, e de circuito aberto. */
    public int requestCount() {
        return requestCount.get();
    }

    /** As chaves de idempotência recebidas: é o que permite deduplicar uma retentativa. */
    public List<String> idempotencyKeys() {
        return List.copyOf(idempotencyKeys);
    }

    public StubNotificationServer reset() {
        requestCount.set(0);
        idempotencyKeys.clear();
        behaviour.set(Behaviour.ok());
        return this;
    }

    /** Responde sempre com o status informado. */
    public StubNotificationServer alwaysRespond(int status) {
        behaviour.set(new Behaviour(status, Duration.ZERO, 0));
        return this;
    }

    /** Demora {@code delay} antes de responder — o cenário de timeout. */
    public StubNotificationServer respondSlowly(Duration delay) {
        behaviour.set(new Behaviour(200, delay, 0));
        return this;
    }

    /** Falha as {@code times} primeiras requisições e responde 200 a partir daí. */
    public StubNotificationServer failTimesThenSucceed(int times, int failureStatus) {
        behaviour.set(new Behaviour(failureStatus, Duration.ZERO, times));
        return this;
    }

    private void handle(HttpExchange exchange) {
        int attempt = requestCount.incrementAndGet();
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        if (key != null) {
            idempotencyKeys.add(key);
        }

        Behaviour current = behaviour.get();
        try (exchange) {
            drain(exchange);
            sleep(current.delay());

            int status = attempt <= current.failFirst() || current.failFirst() == 0
                    ? current.status()
                    : 200;
            exchange.sendResponseHeaders(status, -1);

        } catch (IOException clientWentAway) {
            // Esperado quando o cliente desiste por timeout antes da resposta.
        }
    }

    private static void drain(HttpExchange exchange) throws IOException {
        try (var body = exchange.getRequestBody()) {
            body.readAllBytes();
        }
    }

    private static void sleep(Duration delay) {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /**
     * @param status    status devolvido enquanto a falha durar
     * @param delay     quanto tempo dormir antes de responder
     * @param failFirst quantas requisições falham antes de o servidor voltar ao normal;
     *                  {@code 0} significa "sempre este status"
     */
    private record Behaviour(int status, Duration delay, int failFirst) {

        static Behaviour ok() {
            return new Behaviour(200, Duration.ZERO, 0);
        }
    }
}
