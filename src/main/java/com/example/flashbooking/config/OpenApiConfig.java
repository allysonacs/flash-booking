package com.example.flashbooking.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metadados da especificação OpenAPI servida em {@code /v3/api-docs} e navegável em
 * {@code /swagger-ui.html}.
 *
 * <p>Os endpoints, parâmetros e respostas vêm dos próprios controllers — anotados nas
 * interfaces {@code EventApi} e {@code ReservationApi}, para que a documentação não se
 * misture ao código que atende a requisição. Aqui fica o que é global: título, descrição,
 * a ordem das seções e os exemplos de erro de {@link ApiExamples}, referenciados pelo nome.
 */
@Configuration
public class OpenApiConfig {

    public static final String EVENTS_TAG = "Eventos";
    public static final String RESERVATIONS_TAG = "Reservas";

    @Bean
    public OpenAPI flashBookingOpenApi() {
        return new OpenAPI()
                .components(new Components().examples(ApiExamples.all()))
                .info(new Info()
                        .title("Flash Booking API")
                        .version("v1")
                        .description("""
                                Reserva de ingressos para eventos com capacidade limitada, em \
                                modelo flash sale. A garantia central é que **nunca se vende \
                                mais ingressos do que a capacidade do evento**, independentemente \
                                de quantas instâncias da API estejam no ar.

                                **Fluxo:** crie um evento → reserve ingressos (a reserva nasce \
                                `PENDING` e expira no prazo configurado) → confirme ou cancele.

                                **Erros** seguem a RFC 7807 (`application/problem+json`) e \
                                carregam um `code` estável — programe contra ele, não contra o \
                                texto de `detail`.

                                **Correlação:** envie `X-Correlation-Id` (ou `X-Request-Id`) \
                                para rastrear a requisição nos logs; a resposta sempre devolve \
                                o header, gerado quando ausente."""))
                .tags(List.of(
                        new Tag().name(EVENTS_TAG).description("Criação e consulta de eventos e da disponibilidade"),
                        new Tag().name(RESERVATIONS_TAG).description("Ciclo de vida da reserva: criar, consultar, confirmar e cancelar")));
    }
}
