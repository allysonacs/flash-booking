package com.example.flashbooking.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parâmetros da comunicação assíncrona.
 *
 * <p>Nome do tópico, número de partições, grupo de consumo, tamanho de lote e tempos de
 * espera são configuração — nunca constante em classe. Particionamento e grupo, em especial,
 * mudam por ambiente: o que faz sentido em desenvolvimento com uma instância não é o que faz
 * sentido em produção com dez.
 *
 * @param topic     tópico dos eventos de reserva
 * @param relay     parâmetros do relay que drena o outbox
 * @param consumer  parâmetros do consumidor
 * @param retention limpeza das tabelas de mensageria
 */
@ConfigurationProperties(prefix = "flash-booking.events")
public record EventProperties(

        @DefaultValue Topic topic,
        @DefaultValue Relay relay,
        @DefaultValue Consumer consumer,
        @DefaultValue Retention retention) {

    /**
     * Fila e marcas de idempotência são dados operacionais, não histórico — sem limpeza,
     * as duas tabelas crescem para sempre dentro do banco que sustenta a venda.
     *
     * @param enabled          liga a limpeza periódica
     * @param interval         intervalo entre passadas
     * @param batchSize        linhas removidas por passada, para não segurar locks longos
     * @param publishedFor     por quanto tempo guardar mensagens já publicadas
     * @param processedEventsFor por quanto tempo guardar as marcas de idempotência. Precisa
     *                         ser <strong>maior que a retenção do tópico</strong>: enquanto
     *                         o Kafka puder reentregar a mensagem, a marca precisa existir
     */
    public record Retention(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1h") Duration interval,
            @DefaultValue("5000") int batchSize,
            @DefaultValue("7 days") String publishedFor,
            @DefaultValue("14 days") String processedEventsFor) {
    }

    /**
     * @param name       nome do tópico
     * @param partitions número de partições. É o teto de paralelismo do consumo: um grupo
     *                   nunca processa em paralelo mais do que o número de partições
     * @param replicas   fator de replicação. {@code 1} só é aceitável em desenvolvimento;
     *                   em produção, replicação menor que 3 significa perder mensagens com
     *                   um broker
     * @param autoCreate criar o tópico na subida da aplicação. Conveniente em
     *                   desenvolvimento; em produção o tópico costuma ser provisionado fora
     */
    public record Topic(
            @DefaultValue("flash-booking.reservations.created") String name,
            @DefaultValue("3") int partitions,
            @DefaultValue("1") short replicas,
            @DefaultValue("true") boolean autoCreate) {
    }

    /**
     * @param enabled          liga o relay nesta instância
     * @param interval         intervalo entre passadas, contado a partir do fim da anterior
     * @param batchSize        mensagens reivindicadas por transação
     * @param maxBatchesPerRun teto de lotes por execução
     * @param publishTimeout   quanto esperar pela confirmação do broker antes de considerar
     *                         a tentativa falha e deixar a mensagem para a próxima passada
     */
    public record Relay(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("PT1S") Duration interval,
            @DefaultValue("100") int batchSize,
            @DefaultValue("10") int maxBatchesPerRun,
            @DefaultValue("5s") Duration publishTimeout) {
    }

    /**
     * @param groupId       grupo de consumo. É ele que define o que é "o consumidor": todas
     *                      as instâncias com o mesmo grupo dividem as partições entre si e
     *                      processam cada mensagem uma vez; grupos diferentes recebem cópias
     *                      independentes
     * @param retryAttempts tentativas de reentrega local antes de desistir da mensagem
     * @param retryBackoff  espera entre as tentativas locais
     */
    public record Consumer(
            @DefaultValue("flash-booking.notification-dispatcher") String groupId,
            @DefaultValue("3") int retryAttempts,
            @DefaultValue("500ms") Duration retryBackoff) {
    }
}
