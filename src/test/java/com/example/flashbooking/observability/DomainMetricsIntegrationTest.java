package com.example.flashbooking.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.flashbooking.dto.request.CreateEventRequest;
import com.example.flashbooking.dto.request.CreateReservationRequest;
import com.example.flashbooking.exception.InsufficientCapacityException;
import com.example.flashbooking.service.EventService;
import com.example.flashbooking.service.ReservationExpirationService;
import com.example.flashbooking.service.ReservationService;
import com.example.flashbooking.support.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * As métricas que respondem perguntas de operação durante uma flash sale.
 *
 * <p>A que mais importa é a recusa por esgotamento: em HTTP ela é um {@code 409} igual a
 * qualquer outra regra violada, então sem uma métrica própria não há como distinguir "o
 * evento vendeu tudo" de "alguma coisa quebrou" olhando o painel.
 */
class DomainMetricsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EventService eventService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationExpirationService expirationService;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("venda, confirmação, recusa, cancelamento e expiração são contados separadamente")
    void domainOutcomesAreCounted() {
        double createdBefore = count("flashbooking.reservations.created");
        double confirmedBefore = count("flashbooking.reservations.confirmed");
        double soldOutBefore = countWithReason("sold_out");
        double cancelledBefore = count("flashbooking.reservations.cancelled");
        double expiredBefore = count("flashbooking.reservations.expired");

        UUID eventId = eventService.create(new CreateEventRequest("Evento medido", 12)).id();

        UUID toConfirm = reserve(eventId, 2);
        UUID toCancel = reserve(eventId, 4);
        UUID toExpire = reserve(eventId, 4);

        assertThatThrownBy(() -> reserve(eventId, 9))
                .isInstanceOf(InsufficientCapacityException.class);

        reservationService.confirm(toConfirm);
        // Confirmar de novo não é uma segunda venda: a contagem não pode andar.
        reservationService.confirm(toConfirm);

        reservationService.cancel(toCancel);

        jdbcTemplate.update(
                "UPDATE reservations SET expires_at = now() - interval '1 minute' WHERE id = ?",
                toExpire);
        expirationService.expireDueReservations(10);

        assertThat(count("flashbooking.reservations.created")).isEqualTo(createdBefore + 3);
        assertThat(count("flashbooking.reservations.confirmed")).isEqualTo(confirmedBefore + 1);
        assertThat(countWithReason("sold_out")).isEqualTo(soldOutBefore + 1);
        assertThat(count("flashbooking.reservations.cancelled")).isEqualTo(cancelledBefore + 1);
        assertThat(count("flashbooking.reservations.expired")).isEqualTo(expiredBefore + 1);

        // Assentos devolvidos são contados pela causa: cancelamento e expiração são
        // fenômenos diferentes, e confundir os dois esconde um funil de compra quebrado.
        assertThat(seatsReleased("cancellation")).isPositive();
        assertThat(seatsReleased("expiration")).isPositive();

        // A confirmação não devolve assento nenhum — ela os torna definitivos. Contá-la como
        // devolução inverteria o sinal da métrica que mede o funil.
        assertThat(count("flashbooking.seats.confirmed")).isPositive();
    }

    @Test
    @DisplayName("a latência HTTP e o pool de conexões vêm do Actuator, sem código nosso")
    void infrastructureMetricsComeForFree() {
        // O ponto do teste é documental: não reimplementamos o que a plataforma já mede.
        assertThat(registry.find("hikaricp.connections.active").meter()).isNotNull();
        assertThat(registry.find("jvm.memory.used").meter()).isNotNull();
    }

    private UUID reserve(UUID eventId, int quantity) {
        return reservationService.create(eventId, new CreateReservationRequest(quantity), null)
                .reservation().id();
    }

    private double count(String name) {
        try {
            return registry.get(name).counter().count();
        } catch (MeterNotFoundException notYetRegistered) {
            return 0;
        }
    }

    private double countWithReason(String reason) {
        try {
            return registry.get("flashbooking.reservations.rejected")
                    .tag("reason", reason).counter().count();
        } catch (MeterNotFoundException notYetRegistered) {
            return 0;
        }
    }

    private double seatsReleased(String cause) {
        return registry.get("flashbooking.seats.released").tag("cause", cause).counter().count();
    }
}
