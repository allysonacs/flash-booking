package com.example.flashbooking.config;

import static com.example.flashbooking.exception.GlobalExceptionHandler.DETAIL_INVALID_FIELDS;
import static com.example.flashbooking.exception.GlobalExceptionHandler.INVALID_PARAMETER;
import static com.example.flashbooking.exception.GlobalExceptionHandler.TITLE_BAD_REQUEST;
import static com.example.flashbooking.exception.GlobalExceptionHandler.TITLE_BUSINESS_RULE;
import static com.example.flashbooking.exception.GlobalExceptionHandler.TITLE_NOT_FOUND;
import static com.example.flashbooking.exception.GlobalExceptionHandler.VALIDATION_ERROR;
import static com.example.flashbooking.exception.GlobalExceptionHandler.invalidParameterDetail;

import com.example.flashbooking.dto.response.ProblemResponse;
import com.example.flashbooking.dto.response.ProblemResponse.Violation;
import com.example.flashbooking.entity.EventStatus;
import com.example.flashbooking.entity.ReservationStatus;
import com.example.flashbooking.exception.DomainException;
import com.example.flashbooking.exception.EventNotFoundException;
import com.example.flashbooking.exception.EventNotOpenForReservationException;
import com.example.flashbooking.exception.IdempotencyKeyConflictException;
import com.example.flashbooking.exception.InsufficientCapacityException;
import com.example.flashbooking.exception.InvalidReservationStateException;
import com.example.flashbooking.exception.ReservationExpiredException;
import com.example.flashbooking.exception.ReservationNotFoundException;
import io.swagger.v3.oas.models.examples.Example;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Exemplos de resposta de erro da especificação OpenAPI, construídos como objetos.
 *
 * <p>Cada exemplo é um {@link ProblemResponse} montado com o código e a mensagem da própria
 * exceção de domínio e com os títulos do
 * {@link com.example.flashbooking.exception.GlobalExceptionHandler}. Assim o exemplo não é uma
 * cópia do contrato, e sim o contrato: se o formato do erro ou uma mensagem mudar, o exemplo
 * muda junto — ou deixa de compilar.
 *
 * <p>Os exemplos são registrados em {@code components/examples} por {@link OpenApiConfig}, e as
 * interfaces dos controllers os referenciam pelo nome, com as constantes abaixo:
 * {@code @ExampleObject(name = "...", ref = ApiExamples.EVENT_NOT_FOUND)}.
 */
public final class ApiExamples {

    public static final String EVENT_VALIDATION_ERROR = "EventValidationError";
    public static final String EVENT_INVALID_PARAMETER = "EventInvalidParameter";
    public static final String EVENT_NOT_FOUND = "EventNotFound";

    public static final String RESERVATION_VALIDATION_ERROR = "ReservationValidationError";
    public static final String RESERVATION_EVENT_NOT_FOUND = "ReservationEventNotFound";
    public static final String INSUFFICIENT_CAPACITY = "InsufficientCapacity";
    public static final String EVENT_NOT_OPEN_FOR_RESERVATION = "EventNotOpenForReservation";
    public static final String IDEMPOTENCY_KEY_CONFLICT = "IdempotencyKeyConflict";

    public static final String RESERVATION_INVALID_PARAMETER = "ReservationInvalidParameter";
    public static final String RESERVATION_NOT_FOUND = "ReservationNotFound";
    public static final String RESERVATION_EXPIRED = "ReservationExpired";
    public static final String CONFIRM_INVALID_RESERVATION_STATE = "ConfirmInvalidReservationState";
    public static final String CANCEL_INVALID_RESERVATION_STATE = "CancelInvalidReservationState";

    private static final UUID EVENT_ID = UUID.fromString("7f000001-a093-17ce-81a0-9327d71f0000");
    private static final UUID RESERVATION_ID = UUID.fromString("7f000001-a093-17ce-81a0-9327d8580001");
    private static final Instant TIMESTAMP = Instant.parse("2026-09-12T01:07:37.476253Z");

    private static final String EVENT_PATH = "/events/" + EVENT_ID;
    private static final String EVENT_RESERVATIONS_PATH = EVENT_PATH + "/reservations";
    private static final String RESERVATION_PATH = "/reservations/" + RESERVATION_ID;

    private ApiExamples() {
    }

    /** Todos os exemplos, indexados pelo nome usado nas referências. */
    static Map<String, Example> all() {
        Map<String, Example> examples = new LinkedHashMap<>();

        // --- eventos ---------------------------------------------------------
        add(examples, EVENT_VALIDATION_ERROR, validationError("/events",
                new Violation("capacity", "capacity deve ser maior que zero"),
                new Violation("name", "name é obrigatório")));
        add(examples, EVENT_INVALID_PARAMETER, invalidParameter("/events/abc"));
        add(examples, EVENT_NOT_FOUND, notFound(new EventNotFoundException(EVENT_ID), EVENT_PATH));

        // --- criação de reserva ----------------------------------------------
        add(examples, RESERVATION_VALIDATION_ERROR, validationError(EVENT_RESERVATIONS_PATH,
                new Violation("quantity", "quantity deve ser maior que zero")));
        add(examples, RESERVATION_EVENT_NOT_FOUND,
                notFound(new EventNotFoundException(EVENT_ID), EVENT_RESERVATIONS_PATH));
        add(examples, INSUFFICIENT_CAPACITY,
                businessRule(new InsufficientCapacityException(EVENT_ID, 2), EVENT_RESERVATIONS_PATH));
        add(examples, EVENT_NOT_OPEN_FOR_RESERVATION, businessRule(
                new EventNotOpenForReservationException(EVENT_ID, EventStatus.CLOSED), EVENT_RESERVATIONS_PATH));
        add(examples, IDEMPOTENCY_KEY_CONFLICT,
                businessRule(new IdempotencyKeyConflictException("pedido-42"), EVENT_RESERVATIONS_PATH));

        // --- operações sobre a reserva ---------------------------------------
        add(examples, RESERVATION_INVALID_PARAMETER, invalidParameter("/reservations/abc"));
        add(examples, RESERVATION_NOT_FOUND,
                notFound(new ReservationNotFoundException(RESERVATION_ID), RESERVATION_PATH));
        add(examples, RESERVATION_EXPIRED, businessRule(
                new ReservationExpiredException(RESERVATION_ID), RESERVATION_PATH + "/confirmation"));
        add(examples, CONFIRM_INVALID_RESERVATION_STATE, businessRule(
                new InvalidReservationStateException(RESERVATION_ID, ReservationStatus.CANCELLED, "confirmar"),
                RESERVATION_PATH + "/confirmation"));
        add(examples, CANCEL_INVALID_RESERVATION_STATE, businessRule(
                new InvalidReservationStateException(RESERVATION_ID, ReservationStatus.CONFIRMED, "cancelar"),
                RESERVATION_PATH));

        return examples;
    }

    private static void add(Map<String, Example> examples, String name, ProblemResponse problem) {
        examples.put(name, new Example().summary(problem.code()).value(problem));
    }

    private static ProblemResponse validationError(String instance, Violation... violations) {
        return new ProblemResponse(TITLE_BAD_REQUEST, HttpStatus.BAD_REQUEST.value(), DETAIL_INVALID_FIELDS,
                instance, VALIDATION_ERROR, TIMESTAMP, List.of(violations));
    }

    private static ProblemResponse invalidParameter(String instance) {
        return new ProblemResponse(TITLE_BAD_REQUEST, HttpStatus.BAD_REQUEST.value(), invalidParameterDetail("id"),
                instance, INVALID_PARAMETER, TIMESTAMP, null);
    }

    private static ProblemResponse notFound(DomainException exception, String instance) {
        return fromDomain(exception, HttpStatus.NOT_FOUND, TITLE_NOT_FOUND, instance);
    }

    private static ProblemResponse businessRule(DomainException exception, String instance) {
        return fromDomain(exception, HttpStatus.CONFLICT, TITLE_BUSINESS_RULE, instance);
    }

    private static ProblemResponse fromDomain(DomainException exception, HttpStatus status, String title,
                                              String instance) {
        return new ProblemResponse(title, status.value(), exception.getMessage(), instance,
                exception.getErrorCode(), TIMESTAMP, null);
    }
}
