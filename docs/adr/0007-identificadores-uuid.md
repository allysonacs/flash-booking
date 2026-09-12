# ADR-0007 — Identificadores UUID gerados pela aplicação

- **Status:** Aceita
- **Data:** 2026-09-11

## Contexto

A alternativa natural seria `BIGSERIAL`: menor, mais rápido de indexar e familiar. Mas o id
só existiria depois do `INSERT`, e a aplicação precisa dele **antes do commit** para amarrar
reserva, chave de idempotência e evento de domínio dentro da mesma transação — sem ele, o
outbox e a resposta idempotente dependeriam de um valor que só o banco conhece.

## Decisão

`UUID` como chave primária, gerado pela aplicação com `@UuidGenerator(style = TIME)` do
Hibernate — estratégia baseada em tempo, que coloca o timestamp nos bits mais significativos.

A escolha do `style` é deliberada: o UUID aleatório (v4) espalha as inserções por toda a
árvore do índice e o fragmenta exatamente sob o volume de escrita de uma flash sale. A
estratégia temporal mantém inserções consecutivas próximas no B-tree, e há um teste de
integração que verifica essa ordenação.

## Consequências

- ✅ O id existe antes do commit, sem round-trip extra e sem ponto central de geração.
- ✅ Identificadores não são adivinháveis nem revelam volume de vendas, ao contrário de uma
  sequência exposta na API.
- ✅ Preserva localidade de escrita no índice, diferentemente do UUID v4.
- ⚠️ 16 bytes por chave contra 8 de um `BIGINT`, em todo índice e toda chave estrangeira.
- ⚠️ A estratégia do Hibernate produz um UUID de versão 1, não a versão 7 do RFC 9562; a
  propriedade que interessa (ordenação temporal) foi verificada em teste, e não presumida.
