package com.example.flashbooking.entity;

/**
 * Ciclo de vida de uma reserva.
 *
 * <pre>
 *   PENDING ──▶ CONFIRMED      (confirmação do cliente, dentro do prazo)
 *      │   └──▶ EXPIRED        (prazo vencido — varredura agendada)
 *      └──────▶ CANCELLED      (cancelamento explícito)
 * </pre>
 *
 * <p>Toda reserva nasce {@code PENDING}. As transições que saem daí são
 * <strong>mutuamente exclusivas e definitivas</strong>: aplicada uma, nenhuma outra se
 * aplica, porque todas carregam {@code status = 'PENDING'} na cláusula {@code WHERE} do
 * {@code UPDATE} que as executa. É o banco, e não a aplicação, que impede uma reserva de ser
 * cancelada e expirada ao mesmo tempo — e, com isso, de devolver o mesmo assento duas vezes.
 *
 * <p>As três transições estão implementadas: {@code CONFIRMED} pela confirmação do cliente,
 * {@code CANCELLED} pelo cancelamento explícito e {@code EXPIRED} pela varredura de vencidas.
 *
 * <p>A confirmação é a única delas que <strong>não mexe no inventário</strong>: os assentos já
 * foram comprometidos na criação da reserva, e confirmar apenas impede que voltem ao estoque.
 * As outras duas devolvem os assentos — daí a importância de exatamente uma se aplicar.
 *
 * <p>A confirmação carrega ainda uma segunda guarda, além do estado: ela só é aceita enquanto
 * {@code expires_at > now()}. Sem isso, uma confirmação que chegasse depois do prazo mas antes
 * da varredura tiraria a reserva de {@code PENDING} e a varredura nunca mais a encontraria —
 * o TTL viraria uma sugestão, e não um limite.
 */
public enum ReservationStatus {

    /** Assentos comprometidos, aguardando confirmação até {@code expiresAt}. */
    PENDING,

    /** Confirmada dentro do prazo; os assentos estão vendidos e não voltam ao estoque. */
    CONFIRMED,

    /** Cancelada explicitamente; os assentos já voltaram para o inventário. */
    CANCELLED,

    /** Prazo vencido sem confirmação; os assentos já voltaram para o inventário. */
    EXPIRED
}
