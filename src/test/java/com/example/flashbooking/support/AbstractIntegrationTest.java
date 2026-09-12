package com.example.flashbooking.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base dos testes de integração: sobe a aplicação contra um PostgreSQL real.
 *
 * <p>O container é um <em>singleton</em> iniciado uma única vez por execução da JVM, e não
 * um container por classe de teste. Isso importa por dois motivos: subir o PostgreSQL
 * repetidamente domina o tempo da suíte, e uma URL de conexão estável permite que o Spring
 * reaproveite o mesmo contexto entre as classes. O container é encerrado pelo Ryuk quando
 * a JVM termina.
 *
 * <p>Como o banco é compartilhado entre as classes, cada teste começa com as tabelas de
 * domínio vazias. A limpeza é centralizada aqui, e não em cada classe, por uma lição
 * aprendida: uma limpeza local que apaga "tudo" apaga também o que outra classe acabou de
 * gravar, e o resultado é um teste que falha dependendo da ordem de execução.
 *
 * <p>A imagem é a mesma declarada no {@code docker-compose.yml}: teste e desenvolvimento
 * rodam contra a mesma versão de banco.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    private JdbcTemplate databaseCleaner;

    /**
     * Esvazia as tabelas de domínio antes de cada teste, na ordem em que as chaves
     * estrangeiras permitem. O histórico do Flyway não é tocado: o schema continua sendo o
     * que as migrations produziram.
     */
    @BeforeEach
    void clearDomainTables() {
        databaseCleaner.execute("""
                TRUNCATE TABLE processed_events, outbox_messages, reservations,
                               event_inventory, events CASCADE
                """);
    }
}
