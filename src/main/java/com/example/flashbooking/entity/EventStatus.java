package com.example.flashbooking.entity;

/**
 * Ciclo de vida comercial de um evento.
 *
 * <p>Só existem aqui estados <strong>decididos por um operador</strong>. Estados que podem
 * ser derivados do inventário — "esgotado", por exemplo — deliberadamente não entram: uma
 * cópia denormalizada de informação derivada precisa ser mantida em sincronia sob
 * concorrência, e é exatamente aí que sistemas de venda de ingresso divergem da verdade.
 */
public enum EventStatus {

    /** Aceitando reservas. */
    ON_SALE,

    /** Venda encerrada; o evento continua existindo e consultável. */
    CLOSED,

    /** Evento cancelado; nenhuma reserva nova é aceita. */
    CANCELLED
}
