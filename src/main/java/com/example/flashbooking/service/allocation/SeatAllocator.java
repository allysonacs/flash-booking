package com.example.flashbooking.service.allocation;

import java.util.UUID;

/**
 * Estratégia de alocação de assentos — o ponto do sistema onde a corrida é decidida.
 *
 * <p>Esta é a única abstração do projeto criada por antecipação, e ela se paga: é ela que
 * permite substituir a estratégia correta por uma ingênua em teste e <strong>provar</strong>
 * que o oversell aparece quando a estratégia muda. Sem um ponto de troca, o teste de
 * concorrência provaria apenas que o sistema passou — não que é a estratégia que o salva.
 *
 * <p>Implementações precisam ser seguras sob concorrência entre instâncias diferentes da
 * aplicação, e não apenas entre threads da mesma JVM.
 */
public interface SeatAllocator {

    /**
     * Tenta comprometer {@code quantity} assentos do evento.
     *
     * <p>Retorna {@code false} em vez de lançar exceção quando não há disponibilidade:
     * evento esgotado é resultado normal de uma flash sale, não uma falha. Quem decide como
     * isso vira erro de API é a camada de serviço.
     *
     * @return {@code true} se os assentos foram comprometidos
     */
    boolean tryAllocate(UUID eventId, int quantity);

    /**
     * Devolve {@code quantity} assentos ao inventário.
     *
     * @return {@code true} se a devolução foi aplicada
     */
    boolean release(UUID eventId, int quantity);
}
