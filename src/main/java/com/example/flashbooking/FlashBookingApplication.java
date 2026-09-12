package com.example.flashbooking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Ponto de entrada da aplicação Flash Booking.
 *
 * <p>A aplicação é stateless por design: nenhuma instância mantém estado de negócio em
 * memória, o que permite executar N instâncias simultâneas atrás de um load balancer.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FlashBookingApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlashBookingApplication.class, args);
    }
}
