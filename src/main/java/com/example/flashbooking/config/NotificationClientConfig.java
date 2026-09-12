package com.example.flashbooking.config;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Fiação do cliente do serviço externo de notificação.
 *
 * <p>Os dois timeouts são configurados aqui, no único lugar onde podem realmente ser
 * garantidos — o próprio socket. Um cliente HTTP sem timeout explícito herda o padrão da
 * plataforma, que em geral é "para sempre": basta o outro lado aceitar a conexão e nunca
 * responder para a thread ficar presa indefinidamente. Sob flash sale, threads presas em um
 * serviço secundário é como um serviço secundário derruba o principal.
 *
 * <p>Não há mais pool de despacho próprio: a notificação deixou de ser disparada da thread
 * da requisição e passou a ser consequência de uma mensagem consumida do Kafka. O isolamento
 * que o pool dava, hoje é o próprio consumidor — e com uma garantia melhor, porque a
 * mensagem sobrevive a um restart.
 */
@Configuration
public class NotificationClientConfig {

    @Bean
    public RestClient notificationRestClient(NotificationProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

}
