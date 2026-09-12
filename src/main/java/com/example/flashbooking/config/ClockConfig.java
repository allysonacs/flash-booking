package com.example.flashbooking.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fonte de tempo da aplicação.
 *
 * <p>Existe como bean para que o tempo seja injetável: prazos de reserva testados com
 * {@code Thread.sleep} são lentos e intermitentes, enquanto um relógio substituível torna o
 * teste instantâneo e determinístico. UTC em todo lugar — fuso é problema de apresentação.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
