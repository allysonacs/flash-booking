package com.example.flashbooking.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parâmetros do serviço externo de notificação.
 *
 * <p>Nenhum valor de timeout, URL ou tamanho de fila aparece em código: tudo vive aqui e é
 * sobrescrevível por variável de ambiente. Timeout é justamente o tipo de número que precisa
 * mudar sem recompilar — o que é adequado em um ambiente é errado em outro.
 *
 * @param enabled        liga o envio de notificações; desligado, a aplicação segue vendendo
 *                       normalmente e apenas registra o que teria enviado
 * @param baseUrl        endereço do serviço externo
 * @param path           caminho do recurso que recebe a notificação
 * @param connectTimeout tempo máximo para <em>estabelecer</em> a conexão TCP/TLS
 * @param readTimeout    tempo máximo de espera pela resposta depois da requisição enviada
 */
@ConfigurationProperties(prefix = "flash-booking.notification")
public record NotificationProperties(

        @DefaultValue("true") boolean enabled,
        @DefaultValue("http://localhost:9090") String baseUrl,
        @DefaultValue("/notifications/reservations") String path,
        @DefaultValue("500ms") Duration connectTimeout,
        @DefaultValue("1s") Duration readTimeout) {
}
