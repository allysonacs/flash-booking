package com.example.flashbooking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UuidGenerator;

/**
 * Metadados de um evento com capacidade limitada.
 *
 * <p>Esta entidade guarda apenas informação estável. A contagem de assentos vendidos vive
 * em {@link EventInventory}, em outra tabela: durante a flash sale aquela linha sofre
 * contenção de todas as requisições, e mantê-la separada impede que o lock da venda
 * atrapalhe a leitura dos metadados.
 *
 * <p>Não existe coluna {@code available_capacity}: disponibilidade é informação derivada
 * ({@code total_capacity - reserved_count}) e armazená-la de novo criaria duas fontes da
 * verdade para a única invariante que o sistema não pode perder.
 */
@Entity
@Table(name = "events")
public class Event {

    /**
     * Identificador UUID gerado pela aplicação, com estratégia baseada em tempo.
     *
     * <p>UUID e não sequência do banco porque o id precisa existir antes do commit — é ele
     * que amarra reserva, chave de idempotência e evento de domínio dentro da mesma
     * transação, sem depender de um valor que só o banco conhece. Isso também evita um
     * ponto central de geração quando várias instâncias escrevem em paralelo.
     *
     * <p>{@code Style.TIME} e não o UUID aleatório padrão: a estratégia do Hibernate coloca
     * o timestamp nos bits mais significativos, de modo que ids gerados em sequência caiam
     * próximos no índice B-tree. O UUID v4 espalha as inserções por toda a árvore e
     * fragmenta o índice justamente sob o volume de escrita de uma flash sale.
     */
    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "total_capacity", nullable = false, updatable = false)
    private int totalCapacity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EventStatus status;

    /**
     * Instante de criação obtido do <strong>relógio do banco</strong>, e não do relógio da
     * instância: com várias instâncias rodando, é a única fonte de tempo comum a todas.
     */
    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Event() {
        // exigido pelo JPA
    }

    public Event(String name, int totalCapacity) {
        this.name = Objects.requireNonNull(name, "name");
        if (totalCapacity <= 0) {
            throw new IllegalArgumentException("totalCapacity deve ser maior que zero");
        }
        this.totalCapacity = totalCapacity;
        this.status = EventStatus.ON_SALE;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getTotalCapacity() {
        return totalCapacity;
    }

    public EventStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Igualdade por identidade persistente: duas instâncias representam o mesmo evento
     * quando têm o mesmo id. Entidades ainda não persistidas só são iguais a si mesmas.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Event that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Event.class.hashCode();
    }

    @Override
    public String toString() {
        return "Event{id=%s, name='%s', totalCapacity=%d, status=%s}"
                .formatted(id, name, totalCapacity, status);
    }
}
