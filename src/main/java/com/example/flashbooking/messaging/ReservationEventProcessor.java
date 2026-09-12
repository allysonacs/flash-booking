package com.example.flashbooking.messaging;

import com.example.flashbooking.config.EventProperties;
import com.example.flashbooking.notification.NotificationGateway;
import com.example.flashbooking.notification.ReservationNotification;
import com.example.flashbooking.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O trabalho que um {@link ReservationCreated} desencadeia — feito uma vez só, por mais
 * vezes que a mensagem chegue.
 *
 * <p><strong>Idempotência.</strong> A primeira coisa da transação é reivindicar a mensagem
 * em {@code processed_events}. A inserção usa a chave primária como árbitro
 * ({@code ON CONFLICT DO NOTHING}): quem insere processa, quem colide já foi processado e sai
 * sem fazer nada. Não é uma consulta seguida de uma decisão — entre as duas caberia outra
 * instância do mesmo grupo processando a mesma mensagem.
 *
 * <p><strong>Por que não confiar em "exactly-once".</strong> O Kafka oferece semântica
 * transacional entre tópicos, mas o efeito deste consumidor sai do Kafka: é uma chamada HTTP
 * a um serviço externo. Nenhuma configuração de broker torna isso atômico. Além disso, o
 * próprio relay pode republicar uma mensagem já entregue, e um rebalance pode reentregar o
 * que não teve offset commitado. Tratar entrega repetida como possível não é pessimismo, é
 * a única leitura correta do sistema.
 *
 * <p><strong>Marca e efeito na mesma transação.</strong> Se o trabalho falha, a marca de
 * processamento é desfeita junto — a mensagem volta a ser reentregue e processada de novo,
 * em vez de ficar marcada como feita sem ter sido. O preço é que um efeito externo
 * bem-sucedido seguido de falha no commit será repetido; é para isso que a notificação
 * carrega a própria chave de idempotência.
 */
@Service
public class ReservationEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(ReservationEventProcessor.class);

    private final ProcessedEventRepository processedEventRepository;
    private final NotificationGateway notificationGateway;
    private final String consumerGroup;

    public ReservationEventProcessor(ProcessedEventRepository processedEventRepository,
                                     NotificationGateway notificationGateway,
                                     EventProperties properties) {
        this.processedEventRepository = processedEventRepository;
        this.notificationGateway = notificationGateway;
        this.consumerGroup = properties.consumer().groupId();
    }

    /**
     * @return {@code true} quando esta chamada processou a mensagem, {@code false} quando ela
     *         já havia sido processada
     */
    @Transactional
    public boolean process(ReservationCreated event) {
        if (processedEventRepository.markProcessed(event.messageId(), consumerGroup) == 0) {
            log.debug("Mensagem {} já processada pelo grupo {}; ignorada",
                    event.messageId(), consumerGroup);
            return false;
        }

        notificationGateway.send(new ReservationNotification(
                event.reservationId(), event.eventId(), event.quantity(), event.expiresAt()));

        log.info("Reserva {} processada a partir do evento {}",
                event.reservationId(), event.messageId());
        return true;
    }
}
