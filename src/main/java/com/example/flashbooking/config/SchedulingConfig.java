package com.example.flashbooking.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Habilita as tarefas agendadas da aplicação.
 *
 * <p>Todas as instâncias executam os mesmos jobs ao mesmo tempo, e isso é intencional: a
 * disputa é resolvida no banco com {@code FOR UPDATE SKIP LOCKED}, de modo que nenhuma
 * instância precisa ser eleita "a dona do job" — e nenhuma fica ociosa esperando a outra
 * falhar.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
