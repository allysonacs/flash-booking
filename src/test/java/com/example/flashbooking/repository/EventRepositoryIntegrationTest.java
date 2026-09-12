package com.example.flashbooking.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.flashbooking.entity.Event;
import com.example.flashbooking.entity.EventInventory;
import com.example.flashbooking.entity.EventStatus;
import com.example.flashbooking.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Prova que o caminho Java → JPA/Hibernate → PostgreSQL funciona de ponta a ponta.
 *
 * <p>Nada aqui é simulado: as asserções são feitas contra as linhas efetivamente gravadas
 * em um PostgreSQL de verdade, inclusive lendo-as de volta por SQL puro, fora da sessão do
 * Hibernate — um teste de persistência que confia apenas no cache de primeiro nível não
 * prova que algo chegou ao banco.
 */
class EventRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventInventoryRepository inventoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("o banco alvo é realmente o PostgreSQL, e o schema veio do Flyway")
    void runsAgainstPostgresWithFlywayManagedSchema() {
        String product = jdbcTemplate.execute(
                (org.springframework.jdbc.core.ConnectionCallback<String>) connection ->
                        connection.getMetaData().getDatabaseProductName());

        Integer appliedMigrations = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);

        assertThat(product).isEqualTo("PostgreSQL");
        assertThat(appliedMigrations).isPositive();
    }

    @Test
    @DisplayName("salva um evento e o lê de volta do PostgreSQL")
    void persistsAndReloadsEvent() {
        Event saved = eventRepository.save(new Event("Show de Aniversário", 500));
        UUID id = saved.getId();

        Optional<Event> reloaded = eventRepository.findById(id);

        assertThat(id).isNotNull();
        assertThat(reloaded).isPresent().get().satisfies(event -> {
            assertThat(event.getName()).isEqualTo("Show de Aniversário");
            assertThat(event.getTotalCapacity()).isEqualTo(500);
            assertThat(event.getStatus()).isEqualTo(EventStatus.ON_SALE);
            assertThat(event.getCreatedAt()).isNotNull().isBefore(Instant.now().plusSeconds(1));
        });
    }

    @Test
    @DisplayName("a linha gravada existe no banco quando lida por SQL puro")
    void writesRowVisibleOutsideTheHibernateSession() {
        Event saved = eventRepository.save(new Event("Festival de Verão", 120));

        String storedName = jdbcTemplate.queryForObject(
                "SELECT name FROM events WHERE id = ?", String.class, saved.getId());
        Integer storedCapacity = jdbcTemplate.queryForObject(
                "SELECT total_capacity FROM events WHERE id = ?", Integer.class, saved.getId());

        assertThat(storedName).isEqualTo("Festival de Verão");
        assertThat(storedCapacity).isEqualTo(120);
    }

    @Test
    @DisplayName("identificadores gerados em sequência preservam a ordem temporal no índice")
    void generatesTimeOrderedIdentifiers() {
        List<String> ids = IntStream.range(0, 10)
                .mapToObj(i -> eventRepository.save(new Event("Evento " + i, 10)))
                .map(event -> event.getId().toString())
                .toList();

        // A ordem lexicográfica acompanhar a ordem de criação é o que garante localidade de
        // escrita no índice B-tree — a propriedade que motivou não usar UUID aleatório.
        assertThat(ids).isSorted();
    }

    @Test
    @DisplayName("o inventário nasce zerado e com disponibilidade igual à capacidade")
    void persistsInventoryForEvent() {
        Event event = eventRepository.save(new Event("Peça de Teatro", 80));

        EventInventory inventory = inventoryRepository.save(new EventInventory(event));

        assertThat(inventory.getEventId()).isEqualTo(event.getId());
        assertThat(inventory.getReservedCount()).isZero();
        assertThat(inventory.getAvailableCapacity()).isEqualTo(80);
        assertThat(inventory.getUpdatedAt()).isNotNull();
        assertThat(inventoryRepository.findById(event.getId())).isPresent();
    }

    @Test
    @DisplayName("o PostgreSQL recusa um inventário acima da capacidade, mesmo por SQL direto")
    void databaseRejectsOversellEvenBypassingTheApplication() {
        Event event = eventRepository.save(new Event("Show Esgotado", 2));
        inventoryRepository.save(new EventInventory(event));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE event_inventory SET reserved_count = 3 WHERE event_id = ?", event.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_event_inventory_no_oversell");
    }
}
