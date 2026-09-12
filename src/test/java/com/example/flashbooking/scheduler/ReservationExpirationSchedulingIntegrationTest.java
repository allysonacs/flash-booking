package com.example.flashbooking.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.flashbooking.dto.request.CreateEventRequest;
import com.example.flashbooking.dto.request.CreateReservationRequest;
import com.example.flashbooking.service.EventService;
import com.example.flashbooking.service.ReservationService;
import com.example.flashbooking.support.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.TestPropertySource;

/**
 * Prova que a expiração acontece <strong>sozinha</strong>, sem ninguém chamar o serviço.
 *
 * <p>Os outros testes de expiração invocam a varredura diretamente, o que é determinístico
 * mas não verifica o agendamento. Aqui o scheduler é religado com um intervalo curto e o
 * teste apenas observa: se a fiação estiver errada — `@EnableScheduling` ausente, propriedade
 * com nome trocado, bean condicional desligado — nada acontece e o teste falha por timeout.
 *
 * <p>O contexto é descartado ao fim da classe de propósito. Sem isso, o Spring o manteria em
 * cache e o agendador continuaria varrendo o banco compartilhado enquanto as outras classes
 * rodam — expirando, pelas costas delas, reservas que elas mesmas pretendiam expirar. É a
 * única classe que liga o agendador, e é a única que precisa desligá-lo.
 */
@TestPropertySource(properties = {
        "flash-booking.reservation.expiration.enabled=true",
        "flash-booking.reservation.expiration.interval=PT0.2S"
})
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class ReservationExpirationSchedulingIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private EventService eventService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("a varredura agendada expira a reserva vencida sem intervenção")
    void scheduledSweepExpiresDueReservation() {
        UUID eventId = eventService.create(new CreateEventRequest("Evento agendado", 10)).id();
        UUID reservationId = reservationService
                .create(eventId, new CreateReservationRequest(4), null)
                .reservation().id();

        jdbcTemplate.update(
                "UPDATE reservations SET expires_at = now() - interval '1 minute' WHERE id = ?",
                reservationId);

        awaitStatus(reservationId, "EXPIRED");
        assertThat(reservedCount(eventId)).isZero();
    }

    private void awaitStatus(UUID reservationId, String expectedStatus) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        String status = null;

        while (Instant.now().isBefore(deadline)) {
            status = jdbcTemplate.queryForObject(
                    "SELECT status FROM reservations WHERE id = ?", String.class, reservationId);
            if (expectedStatus.equals(status)) {
                return;
            }
            sleep();
        }
        assertThat(status)
                .withFailMessage("A reserva continuou %s após %s — a varredura agendada não rodou",
                        status, TIMEOUT)
                .isEqualTo(expectedStatus);
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private Integer reservedCount(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT reserved_count FROM event_inventory WHERE event_id = ?", Integer.class, eventId);
    }
}
