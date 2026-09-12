package com.example.flashbooking.support;

import com.example.flashbooking.service.allocation.SeatAllocator;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A implementação <strong>errada</strong> de propósito: lê a disponibilidade, decide em
 * memória e grava o novo valor.
 *
 * <p>Existe apenas em código de teste — é a contraprova do desenho. Sem ela, o teste de
 * concorrência mostraria que o sistema passa, mas não que é a estratégia de alocação que o
 * salva; com ela, é possível demonstrar o oversell aparecendo assim que a estratégia muda.
 * Nada disso vai para o código de produção: uma implementação sabidamente defeituosa no
 * classpath da aplicação é um acidente esperando um {@code @Primary} distraído.
 *
 * <p>A janela entre a leitura e a escrita é alargada artificialmente para que a corrida seja
 * determinística. O defeito não depende disso — a espera apenas garante que o teste falhe
 * sempre, e não de vez em quando.
 */
public class NaiveSeatAllocator implements SeatAllocator {

    private final JdbcTemplate jdbcTemplate;
    private final Duration raceWindow;

    public NaiveSeatAllocator(JdbcTemplate jdbcTemplate, Duration raceWindow) {
        this.jdbcTemplate = jdbcTemplate;
        this.raceWindow = raceWindow;
    }

    @Override
    public boolean tryAllocate(UUID eventId, int quantity) {
        Integer reserved = jdbcTemplate.queryForObject(
                "SELECT reserved_count FROM event_inventory WHERE event_id = ?", Integer.class, eventId);
        Integer total = jdbcTemplate.queryForObject(
                "SELECT total_capacity FROM event_inventory WHERE event_id = ?", Integer.class, eventId);

        if (reserved == null || total == null || reserved + quantity > total) {
            return false;
        }

        sleepThroughTheRaceWindow();

        jdbcTemplate.update("UPDATE event_inventory SET reserved_count = ? WHERE event_id = ?",
                reserved + quantity, eventId);
        return true;
    }

    @Override
    public boolean release(UUID eventId, int quantity) {
        Integer reserved = jdbcTemplate.queryForObject(
                "SELECT reserved_count FROM event_inventory WHERE event_id = ?", Integer.class, eventId);
        if (reserved == null || reserved - quantity < 0) {
            return false;
        }

        sleepThroughTheRaceWindow();

        jdbcTemplate.update("UPDATE event_inventory SET reserved_count = ? WHERE event_id = ?",
                reserved - quantity, eventId);
        return true;
    }

    private void sleepThroughTheRaceWindow() {
        try {
            Thread.sleep(raceWindow.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
