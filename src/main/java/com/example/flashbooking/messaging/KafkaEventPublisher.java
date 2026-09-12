package com.example.flashbooking.messaging;

import com.example.flashbooking.config.EventProperties;
import com.example.flashbooking.entity.OutboxMessage;
import com.example.flashbooking.observability.CorrelationId;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Publica uma mensagem do outbox no Kafka e <strong>espera a confirmação</strong>.
 *
 * <p>A espera é o ponto. O envio do Kafka é assíncrono por natureza; se o relay disparasse e
 * seguisse em frente, ele marcaria como publicada uma mensagem que o broker talvez tenha
 * recusado — e aí sim o evento se perderia, com a linha do outbox dizendo o contrário. O
 * relay só marca o que o broker confirmou.
 *
 * <p>A espera é limitada por {@code publish-timeout}: um broker que não responde não pode
 * segurar a transação do relay indefinidamente. Estourado o prazo, a tentativa conta como
 * falha, a mensagem permanece {@code PENDING} e a próxima passada tenta de novo.
 */
@Component
public class KafkaEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private static final String EVENT_TYPE_HEADER = "event-type";
    private static final String MESSAGE_ID_HEADER = "message-id";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final EventProperties properties;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                               EventProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    /**
     * @throws EventPublishException quando o broker não confirmou dentro do prazo
     */
    public void publish(OutboxMessage message) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                properties.topic().name(),
                // Sem partição explícita: a chave decide. Mesma chave, mesma partição,
                // ordem preservada entre as mensagens daquele show.
                null,
                message.getPartitionKey(),
                message.getPayload());

        record.headers()
                .add(new RecordHeader(EVENT_TYPE_HEADER, bytes(message.getEventType())))
                .add(new RecordHeader(MESSAGE_ID_HEADER, bytes(message.getId().toString())));

        if (message.getCorrelationId() != null) {
            record.headers().add(new RecordHeader(
                    CorrelationId.KAFKA_HEADER, bytes(message.getCorrelationId())));
        }

        try {
            SendResult<String, String> result = kafkaTemplate.send(record)
                    .get(properties.relay().publishTimeout().toMillis(), TimeUnit.MILLISECONDS);

            log.debug("Mensagem {} publicada em {}-{}@{}", message.getId(),
                    result.getRecordMetadata().topic(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());

        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new EventPublishException("Publicação interrompida", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new EventPublishException(
                    "Broker não confirmou a mensagem " + message.getId(), failure);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
