package com.example.flashbooking.observability;

import java.util.UUID;
import org.slf4j.MDC;

/**
 * O identificador que costura uma requisição aos logs que ela produz — inclusive aos que
 * acontecem depois dela, em outra thread ou em outra instância.
 *
 * <p>Com N instâncias atrás de um load balancer, "procurar no log" deixa de ser uma
 * operação possível sem isto: a mesma venda produz linhas na instância que atendeu o HTTP,
 * na que drenou o outbox e na que consumiu a mensagem. O id de correlação é o que permite
 * juntá-las de novo.
 *
 * <p>Fica no {@link MDC} do SLF4J, de onde todo log da thread o herda sem que nenhum
 * {@code log.info} precise citá-lo. O preço disso é a disciplina de <strong>sempre</strong>
 * limpar ao final: thread de pool que sai com MDC sujo carimba a requisição seguinte com o
 * id da anterior, o que é pior do que não ter id nenhum.
 */
public final class CorrelationId {

    /** Chave no MDC e no padrão de log. */
    public static final String MDC_KEY = "correlationId";

    /** Header HTTP de entrada e de saída. */
    public static final String HEADER = "X-Correlation-Id";

    /** Alias aceito na entrada: é o nome que vários proxies e clientes já usam. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** Header da mensagem Kafka, para que a correlação atravesse o broker. */
    public static final String KAFKA_HEADER = "correlation-id";

    private CorrelationId() {
    }

    /** Coloca o valor no contexto de log da thread atual. */
    public static String set(String correlationId) {
        String value = correlationId == null || correlationId.isBlank()
                ? generate()
                : sanitize(correlationId);
        MDC.put(MDC_KEY, value);
        return value;
    }

    /** Gera um id para trabalho que não nasceu de uma requisição (jobs, consumo). */
    public static String startNew(String prefix) {
        String value = prefix + "-" + UUID.randomUUID();
        MDC.put(MDC_KEY, value);
        return value;
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    /**
     * Um id que vem de fora é entrada não confiável: ele vai parar em arquivos de log e em
     * um campo do banco. Corta o tamanho e descarta o que não for alfanumérico simples —
     * um "id" com quebra de linha injeta linhas falsas no log.
     */
    private static String sanitize(String value) {
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "");
        if (cleaned.isBlank()) {
            return generate();
        }
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    private static String generate() {
        return UUID.randomUUID().toString();
    }
}
