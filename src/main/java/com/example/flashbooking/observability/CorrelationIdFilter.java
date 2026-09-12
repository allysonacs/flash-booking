package com.example.flashbooking.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Dá a toda requisição um id de correlação e o devolve ao cliente.
 *
 * <p>Se o chamador já mandou um ({@code X-Correlation-Id} ou {@code X-Request-Id}), ele é
 * reaproveitado — é assim que o rastro atravessa a fronteira entre sistemas em vez de
 * recomeçar em cada salto. Se não mandou, um é gerado.
 *
 * <p>O id volta no header da resposta de propósito: quando um cliente relata "deu erro às
 * 14h32", o id que ele tem em mãos é o que encontra a requisição no log, sem depender de
 * horário aproximado nem de busca por texto.
 *
 * <p>Roda antes de tudo ({@code HIGHEST_PRECEDENCE}) para que até as falhas de validação e o
 * tratamento de erro saiam correlacionados.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(CorrelationId.HEADER);
        if (incoming == null) {
            incoming = request.getHeader(CorrelationId.REQUEST_ID_HEADER);
        }

        String correlationId = CorrelationId.set(incoming);
        response.setHeader(CorrelationId.HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Sem isto, a próxima requisição atendida por esta thread herdaria o id desta.
            CorrelationId.clear();
        }
    }
}
