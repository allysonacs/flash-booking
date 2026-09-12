package com.example.flashbooking.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.jspecify.annotations.Nullable;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Ponto único de tradução entre falhas e respostas HTTP.
 *
 * <p>Todas as respostas de erro seguem a RFC 7807 ({@code application/problem+json}) e
 * carregam duas extensões: {@code code}, um código estável para o cliente programar em cima,
 * e {@code timestamp}. Nenhuma expõe stack trace ou mensagem de exceção interna — detalhe de
 * implementação vazado em corpo de erro é informação para quem ataca, não para quem integra.
 *
 * <p>A classe estende {@link ResponseEntityExceptionHandler} de propósito. O Spring MVC já
 * lança exceções com semântica HTTP correta para rota inexistente (404), método não
 * suportado (405) ou mídia incompatível (415); herdar o tratamento delas garante esses
 * status sem enumerar uma a uma — e evita que caiam no handler genérico e virem 500. O que
 * este handler faz por cima é uniformizar o corpo: mesmo formato, mesmo {@code code}, mesmo
 * {@code timestamp}, venham as falhas do framework ou do domínio.
 *
 * <p>As categorias do contrato são quatro:
 *
 * <ul>
 *   <li><strong>Validação</strong> (400) — o request não satisfaz o contrato;
 *   <li><strong>Recurso não encontrado</strong> (404);
 *   <li><strong>Regra de negócio</strong> (409) — o request é válido, mas o estado do
 *       sistema não permite a operação;
 *   <li><strong>Erro inesperado</strong> (500) — registrado com stack trace no log,
 *       respondido de forma genérica.
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Públicos para que os exemplos da documentação OpenAPI usem os mesmos valores das
    // respostas reais, em vez de cópias que envelhecem.
    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    public static final String INVALID_PARAMETER = "INVALID_PARAMETER";
    public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    public static final String TITLE_BAD_REQUEST = "Requisição inválida";
    public static final String TITLE_NOT_FOUND = "Recurso não encontrado";
    public static final String TITLE_BUSINESS_RULE = "Operação não permitida";
    public static final String DETAIL_INVALID_FIELDS = "Um ou mais campos são inválidos";

    /** Detalhe de um parâmetro de rota com formato incompatível. */
    public static String invalidParameterDetail(String parameterName) {
        return "O parâmetro '%s' tem formato inválido".formatted(parameterName);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFound(ResourceNotFoundException exception,
                                                HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, exception.getErrorCode(),
                TITLE_NOT_FOUND, exception.getMessage(), request.getRequestURI());
    }

    /**
     * Demais violações de regra de negócio: o request é válido, mas o estado atual do
     * sistema não permite a operação. É por aqui que entram, nas próximas fases, casos como
     * inventário insuficiente e transição de estado inválida.
     */
    @ExceptionHandler(DomainException.class)
    public ProblemDetail handleDomain(DomainException exception, HttpServletRequest request) {
        log.info("Regra de negócio violada [{}]: {}", exception.getErrorCode(), exception.getMessage());
        return problem(HttpStatus.CONFLICT, exception.getErrorCode(),
                TITLE_BUSINESS_RULE, exception.getMessage(), request.getRequestURI());
    }

    /**
     * Rede de segurança. O que chega aqui é defeito — as falhas com semântica HTTP conhecida
     * já foram tratadas acima. Fica registrado com stack trace no log e é respondido ao
     * cliente sem nenhum detalhe interno.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Falha inesperada em {} {}", request.getMethod(), request.getRequestURI(), exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR, "Erro interno",
                "Não foi possível processar a requisição", request.getRequestURI());
    }

    /** Campos que não satisfazem as restrições declaradas no DTO de entrada. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        ResponseEntity<Object> response = super.handleMethodArgumentNotValid(exception, headers, status, request);

        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            List<FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                    .map(FieldViolation::from)
                    .sorted(Comparator.comparing(FieldViolation::field))
                    .toList();

            problem.setTitle(TITLE_BAD_REQUEST);
            problem.setDetail(DETAIL_INVALID_FIELDS);
            problem.setProperty("errors", violations);
        }
        return response;
    }

    /**
     * Restrições violadas em parâmetros do método do controller — header, path variable ou
     * o próprio corpo.
     *
     * <p>O Spring troca o tipo da exceção assim que o método tem qualquer parâmetro
     * anotado com restrição: o mesmo corpo inválido que gera {@code MethodArgumentNotValid}
     * em um endpoint gera {@code HandlerMethodValidation} em outro. Para o cliente isso é
     * detalhe interno — os dois caminhos produzem aqui exatamente o mesmo {@code errors[]}.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        ResponseEntity<Object> response =
                super.handleHandlerMethodValidationException(exception, headers, status, request);

        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            List<FieldViolation> violations = new ArrayList<>();
            for (ParameterValidationResult result : exception.getParameterValidationResults()) {
                if (result instanceof ParameterErrors parameterErrors) {
                    parameterErrors.getFieldErrors().stream()
                            .map(FieldViolation::from)
                            .forEach(violations::add);
                } else {
                    result.getResolvableErrors().stream()
                            .map(error -> new FieldViolation(nameOf(result), error.getDefaultMessage()))
                            .forEach(violations::add);
                }
            }
            violations.sort(Comparator.comparing(FieldViolation::field));

            problem.setTitle(TITLE_BAD_REQUEST);
            problem.setDetail(DETAIL_INVALID_FIELDS);
            problem.setProperty("errors", violations);
        }
        return response;
    }

    /** Nome pelo qual o cliente conhece o parâmetro — o header, quando for um header. */
    private static String nameOf(ParameterValidationResult result) {
        RequestHeader header = result.getMethodParameter().getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.value().isBlank()) {
            return header.value();
        }
        String parameterName = result.getMethodParameter().getParameterName();
        return parameterName != null ? parameterName : "request";
    }

    /** Corpo ausente, JSON malformado ou tipo incompatível com o contrato. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        log.debug("Corpo de requisição ilegível", exception);
        ResponseEntity<Object> response = super.handleHttpMessageNotReadable(exception, headers, status, request);

        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            problem.setTitle(TITLE_BAD_REQUEST);
            problem.setDetail("O corpo da requisição não pôde ser interpretado");
        }
        return response;
    }

    /** Parâmetro de rota com formato incompatível — um id que não é UUID, por exemplo. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        ResponseEntity<Object> response = super.handleTypeMismatch(exception, headers, status, request);

        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            problem.setTitle(TITLE_BAD_REQUEST);
            problem.setDetail(invalidParameterDetail(exception.getPropertyName()));
        }
        return response;
    }

    /**
     * Enriquece com {@code code}, {@code timestamp} e {@code instance} toda resposta
     * produzida pelo tratamento herdado — é o que mantém um formato único de erro,
     * independentemente de a falha ter nascido no framework ou no domínio.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, @Nullable Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {

        ResponseEntity<Object> response =
                super.handleExceptionInternal(exception, body, headers, statusCode, request);

        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            problem.setProperty("code", codeFor(exception, statusCode));
            problem.setProperty("timestamp", Instant.now());
            if (problem.getInstance() == null && request instanceof ServletWebRequest servletRequest) {
                problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
            }
        }
        return response;
    }

    /**
     * Código estável para as falhas tratadas pelo framework. Os casos com significado
     * próprio são nomeados; para os demais, o próprio nome do status é um código previsível
     * e estável ({@code METHOD_NOT_ALLOWED}, {@code UNSUPPORTED_MEDIA_TYPE}...).
     */
    private static String codeFor(Exception exception, HttpStatusCode statusCode) {
        if (exception instanceof MethodArgumentNotValidException
                || exception instanceof HandlerMethodValidationException) {
            return VALIDATION_ERROR;
        }
        if (exception instanceof HttpMessageNotReadableException) {
            return MALFORMED_REQUEST;
        }
        if (exception instanceof TypeMismatchException) {
            return INVALID_PARAMETER;
        }
        if (exception instanceof NoResourceFoundException) {
            return RESOURCE_NOT_FOUND;
        }
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status != null ? status.name() : INTERNAL_ERROR;
    }

    private ProblemDetail problem(HttpStatus status, String code, String title, String detail,
                                  String instance) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(instance));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /** Um campo inválido e o motivo, no formato em que o cliente recebe. */
    public record FieldViolation(String field, String message) {

        static FieldViolation from(FieldError error) {
            return new FieldViolation(error.getField(), error.getDefaultMessage());
        }
    }
}
