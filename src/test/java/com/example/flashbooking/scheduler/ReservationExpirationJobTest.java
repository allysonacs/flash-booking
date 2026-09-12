package com.example.flashbooking.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.flashbooking.config.ReservationProperties;
import com.example.flashbooking.config.ReservationProperties.Expiration;
import com.example.flashbooking.service.ReservationExpirationService;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Comportamento do laço de drenagem: quantos lotes a varredura processa por execução.
 *
 * <p>O que o job decide é só isso — a correção do que acontece dentro de cada lote é provada
 * contra o PostgreSQL, em {@code ReservationExpirationServiceIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class ReservationExpirationJobTest {

    private static final int BATCH_SIZE = 10;

    @Mock
    private ReservationExpirationService expirationService;

    @Test
    @DisplayName("continua drenando enquanto os lotes vêm cheios")
    void drainsUntilBatchComesBackPartial() {
        when(expirationService.expireDueReservations(BATCH_SIZE)).thenReturn(10, 10, 3);

        jobWith(5).expireDueReservations();

        verify(expirationService, times(3)).expireDueReservations(BATCH_SIZE);
        verifyNoMoreInteractions(expirationService);
    }

    @Test
    @DisplayName("para no teto de lotes por execução, deixando o resto para a próxima")
    void stopsAtTheBatchLimit() {
        when(expirationService.expireDueReservations(BATCH_SIZE)).thenReturn(10);

        jobWith(4).expireDueReservations();

        verify(expirationService, times(4)).expireDueReservations(BATCH_SIZE);
    }

    @Test
    @DisplayName("sem reservas vencidas, uma única consulta por execução")
    void doesNothingWhenThereIsNothingToExpire() {
        when(expirationService.expireDueReservations(BATCH_SIZE)).thenReturn(0);

        jobWith(5).expireDueReservations();

        verify(expirationService, times(1)).expireDueReservations(BATCH_SIZE);
    }

    @Test
    @DisplayName("os parâmetros da varredura têm padrão seguro quando não configurados")
    void hasSaneDefaults() {
        Expiration defaults = new ReservationProperties(Duration.ofMinutes(15),
                new Expiration(200, 10)).expiration();

        assertThat(defaults.batchSize()).isPositive();
        assertThat(defaults.maxBatchesPerRun()).isPositive();
    }

    private ReservationExpirationJob jobWith(int maxBatchesPerRun) {
        return new ReservationExpirationJob(expirationService,
                new ReservationProperties(Duration.ofMinutes(15),
                        new Expiration(BATCH_SIZE, maxBatchesPerRun)));
    }
}
