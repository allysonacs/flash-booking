package com.example.flashbooking.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Fiação do Kafka: tópico e tratamento de erro do consumidor.
 *
 * <p>Produtor e consumidor em si são configurados em {@code application.yml} — serializadores,
 * {@code acks}, idempotência do produtor e commit manual de offset são configuração, não
 * código.
 */
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    /**
     * Cria o tópico na subida, para que desenvolvimento e teste não dependam de um passo
     * manual.
     *
     * <p>Em produção o tópico costuma ser provisionado fora da aplicação — daí a criação ser
     * condicional. Criar tópico automaticamente em produção esconde erro de digitação: um
     * nome errado vira um tópico novo e vazio em vez de uma falha visível.
     */
    @Bean
    @ConditionalOnProperty(name = "flash-booking.events.topic.auto-create",
            havingValue = "true", matchIfMissing = true)
    public NewTopic reservationsTopic(EventProperties properties) {
        EventProperties.Topic topic = properties.topic();
        log.info("Tópico {} com {} partição(ões) e réplica(s) {}",
                topic.name(), topic.partitions(), topic.replicas());

        return TopicBuilder.name(topic.name())
                .partitions(topic.partitions())
                .replicas(topic.replicas())
                .build();
    }

    /**
     * O que acontece quando o processamento de uma mensagem falha.
     *
     * <p>Reentrega local com backoff fixo: a falha típica é transitória (o banco engasgou, o
     * serviço externo piscou), e tentar de novo em meio segundo costuma resolver sem envolver
     * o broker.
     *
     * <p>Esgotadas as tentativas, a mensagem é registrada e o offset avança. A alternativa —
     * insistir para sempre — pararia a partição inteira por causa de uma mensagem, o que
     * transforma um defeito pontual em indisponibilidade do consumo. O destino natural dela é
     * uma <em>dead letter topic</em>; enquanto ela não existe, o log é o registro, e a
     * reentrega pode ser feita a partir do próprio outbox, que guarda o evento original.
     *
     * <p>Nada disso mascara erro de negócio: o consumidor não decide sobre vendas. A venda já
     * aconteceu, no PostgreSQL, antes de a mensagem existir.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(EventProperties properties) {
        EventProperties.Consumer consumer = properties.consumer();
        FixedBackOff backOff = new FixedBackOff(
                consumer.retryBackoff().toMillis(), consumer.retryAttempts() - 1L);

        DefaultErrorHandler handler = new DefaultErrorHandler((record, exception) ->
                log.error("Mensagem descartada após {} tentativas: tópico={} partição={} offset={}",
                        consumer.retryAttempts(), record.topic(), record.partition(),
                        record.offset(), exception), backOff);

        handler.setCommitRecovered(true);
        return handler;
    }
}
