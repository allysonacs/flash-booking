package com.example.flashbooking.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Dispara N tarefas ao mesmo tempo e coleta o resultado de cada uma.
 *
 * <p>O ponto é a <em>simultaneidade</em>: as threads são criadas antes e ficam presas em uma
 * barreira até que todas estejam prontas. Sem essa barreira, as tarefas começariam em
 * sequência e o teste passaria mesmo com uma implementação sujeita a corrida — que é
 * exatamente o falso positivo que este projeto precisa evitar.
 *
 * <p>Usa threads virtuais: centenas de tarefas bloqueadas em I/O de banco não custam
 * centenas de threads de sistema operacional.
 */
public final class ConcurrentRunner {

    private ConcurrentRunner() {
    }

    /**
     * @return o resultado de cada tarefa, na ordem de submissão: o valor devolvido ou a
     *         exceção lançada
     */
    public static <T> List<Outcome<T>> runSimultaneously(int taskCount, Callable<T> task) {
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>(taskCount);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < taskCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }

            awaitAll(ready);
            start.countDown();
        }

        return futures.stream().map(ConcurrentRunner::<T>outcomeOf).toList();
    }

    private static void awaitAll(CountDownLatch ready) {
        try {
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("As tarefas não ficaram prontas a tempo");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static <T> Outcome<T> outcomeOf(Future<T> future) {
        try {
            return new Outcome<>(future.get(60, TimeUnit.SECONDS), null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (Exception failure) {
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            return new Outcome<>(null, cause);
        }
    }

    /** Resultado de uma tarefa: ou um valor, ou a exceção que a interrompeu. */
    public record Outcome<T>(T value, Throwable failure) {

        public boolean succeeded() {
            return failure == null;
        }
    }
}
