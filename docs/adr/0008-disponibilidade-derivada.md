# ADR-0008 — Disponibilidade derivada, não persistida

- **Status:** Aceita
- **Data:** 2026-09-11

## Contexto

O desenho inicial da entidade previa um campo `availableCapacity` em `Event`, ao lado de
`totalCapacity`. É a modelagem mais direta e a que qualquer leitor espera encontrar.

O problema aparece sob concorrência: `availableCapacity` é informação **derivada**
(`total_capacity - reserved_count`). Persistir um valor derivado cria uma segunda fonte da
verdade para a única invariante que o sistema não pode perder — e qualquer caminho de
escrita que atualize uma sem a outra produz divergência silenciosa, do tipo que só aparece
quando o evento lota.

## Decisão

Não existe coluna de disponibilidade. `events` guarda metadados estáveis; `event_inventory`
— tabela separada, uma linha por evento — guarda `total_capacity` e `reserved_count`, com a
`CHECK` constraint que torna o oversell impossível no nível do banco. A disponibilidade é
calculada onde é lida (`EventInventory#getAvailableCapacity`).

O inventário fica em tabela própria, e não como mais uma coluna de `events`, porque é a
linha que sofre contenção de toda a flash sale: isolá-la mantém o registro quente pequeno
(menos WAL e menos bloat sob `UPDATE` intenso) e evita que o lock da venda atrapalhe a
leitura dos metadados.

## Consequências

- ✅ Uma única fonte da verdade para a capacidade comprometida, protegida por constraint.
- ✅ O contador vive isolado, o que abre caminho para particioná-lo em *buckets* se o
  throughput por evento se tornar o gargalo.
- ✅ Atende ao campo `availableCapacity` pedido no enunciado — como valor calculado, que é o
  que ele de fato é.
- ⚠️ Ler evento e disponibilidade custa duas consultas, e criá-los exige escrever em duas
  tabelas na mesma transação.
- ⚠️ A leitura de alto volume de disponibilidade será servida pela projeção eventualmente
  consistente da Fase 5, não por esta tabela.
