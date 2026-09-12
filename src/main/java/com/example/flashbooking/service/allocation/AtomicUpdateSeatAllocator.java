package com.example.flashbooking.service.allocation;

import com.example.flashbooking.repository.EventInventoryRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Estratégia padrão: a decisão de vender é um único {@code UPDATE} condicional no
 * PostgreSQL.
 *
 * <p>Repare no que esta classe <em>não</em> faz: ela não lê o inventário, não compara
 * disponibilidade em memória e não decide nada. Toda a lógica de "cabe?" está dentro do
 * comando SQL, avaliada pelo banco sob o lock da linha. Por isso a correção não depende de
 * quantas instâncias da API estão rodando, nem de qualquer coordenação entre elas.
 *
 * <p>A operação participa da transação de quem chama: se a reserva falhar depois da
 * alocação, o incremento do contador é desfeito no mesmo rollback.
 */
@Component
public class AtomicUpdateSeatAllocator implements SeatAllocator {

    private final EventInventoryRepository inventoryRepository;

    public AtomicUpdateSeatAllocator(EventInventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    @Override
    public boolean tryAllocate(UUID eventId, int quantity) {
        return inventoryRepository.reserveSeats(eventId, quantity) == 1;
    }

    @Override
    public boolean release(UUID eventId, int quantity) {
        return inventoryRepository.releaseSeats(eventId, quantity) == 1;
    }
}
