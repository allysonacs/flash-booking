package com.example.flashbooking;

import com.example.flashbooking.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Garante que o contexto Spring sobe com a configuração real da aplicação.
 *
 * <p>É o teste de fumaça mais barato do projeto — e, agora que há banco, também o mais
 * abrangente: ele só passa se o Flyway aplicar as migrations e se o Hibernate validar o
 * mapeamento contra o schema resultante ({@code ddl-auto=validate}). Qualquer divergência
 * entre entidade e migration falha aqui, não em produção.
 */
class FlashBookingApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }
}
