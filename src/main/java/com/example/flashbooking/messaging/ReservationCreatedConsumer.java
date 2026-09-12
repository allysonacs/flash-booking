package com.example.flashbooking.messaging;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.example.flashbooking.observability.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consome {@link ReservationCreated} do tópico de reservas.
 *
 * <p>O listener faz o mínimo: desserializa, delega e deixa o erro subir. Quem decide o que
 * fazer com a mensagem é {@link ReservationEventProcessor}, em uma transação; quem decide o
 * que fazer com a falha é o error handler configurado — reentrega local com backoff e, se
 * nem assim passar, registro e avanço do offset para que uma mensagem não bloqueie a
 * partição inteira.
 *
 * <p>A exceção é o payload ilegível: aí não há o que tentar de novo. Uma mensagem que não
 * desserializa hoje não vai desserializar daqui a 500 ms, e insistir nela pararia a partição
 * — o clássico <em>poison message</em>. Ela é registrada e descartada.
 *
 * <p>O <strong>grupo de consumo</strong> vem de configuração. É ele que define a unidade de
 * consumo: todas as instâncias da aplicação usam o mesmo grupo, dividem as partições entre
 * si e processam cada mensagem uma vez; um grupo novo (um relatório, uma auditoria) receberia
 * a sua própria cópia do fluxo sem interferir neste.
 */
@Component
public class ReservationCreatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReservationCreatedConsumer.class);

    private final ReservationEventProcessor processor;
    private final ObjectMapper objectMapper;

    public ReservationCreatedConsumer(ReservationEventProcessor processor, ObjectMapper objectMapper) {
        this.processor = processor;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${flash-booking.events.topic.name}",
            groupId = "${flash-booking.events.consumer.group-id}")
    public void onReservationCreated(@Payload String payload,
                                     // Opcional de propósito: mensagem sem chave é
                                     // válida no Kafka. Exigir o header faria um produtor
                                     // terceiro, ou um teste manual pela Kafka UI, derrubar
                                     // o listener antes de qualquer validação nossa.
                                     @Header(name = KafkaHeaders.RECEIVED_KEY, required = false)
                                     String key,
                                     @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                                     @Header(KafkaHeaders.OFFSET) long offset,
                                     @Header(name = CorrelationId.KAFKA_HEADER, required = false)
                                     String correlationId) {

        // O id da requisição original volta ao contexto de log: o que este consumidor
        // registrar é rastreável até a venda que o originou, em outra instância.
        CorrelationId.set(correlationId);
        try {
            ReservationCreated event;
            try {
                event = objectMapper.readValue(payload, ReservationCreated.class);
            } catch (JacksonException unreadable) {
                log.error("Mensagem ilegível em partição {} offset {} (chave {}); descartada: {}",
                        partition, offset, key, unreadable.getMessage());
                return;
            }

            processor.process(event);
        } finally {
            CorrelationId.clear();
        }
    }
}
