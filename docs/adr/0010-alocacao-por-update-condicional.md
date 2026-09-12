# ADR-0010 — Alocação de assentos por `UPDATE` condicional atômico

- **Status:** Aceita
- **Data:** 2026-09-11

## Contexto

Com N instâncias da API atendendo a mesma flash sale, alguém precisa arbitrar quem fica com
o último assento. A implementação intuitiva — ler a disponibilidade, comparar em memória,
gravar o novo valor — é exatamente a que não funciona: entre a leitura e a escrita, outra
transação pode ter vendido o mesmo lugar. Nenhum nível de isolamento padrão impede esse
*lost update*, porque a decisão foi tomada fora do banco.

## Decisão

A aplicação nunca decide se há assento. Ela emite um único comando que **incrementa o
contador apenas se o resultado couber na capacidade** e lê quantas linhas foram afetadas:

```sql
UPDATE event_inventory
   SET reserved_count = reserved_count + :quantity
 WHERE event_id = :eventId
   AND reserved_count + :quantity <= total_capacity
```

`1` significa vendido; `0` significa esgotado — e esgotado não é erro de sistema, é resultado
normal. A devolução no cancelamento é o espelho disso, com a condição
`reserved_count - :quantity >= 0`.

Isolamento `READ COMMITTED`, o padrão do PostgreSQL. Ele basta porque a condição é avaliada
pelo banco sob o lock da linha: quem chega depois bloqueia e, ao ser liberado, **reavalia o
predicado contra a versão já commitada** do contador. A `CHECK` constraint
`reserved_count BETWEEN 0 AND total_capacity` fica como rede de segurança — se um bug futuro
burlar a estratégia, o banco recusa o estado inválido.

### Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| `SELECT ... FOR UPDATE` (pessimista) | Correta, mas custa um round-trip a mais e mantém o lock por mais tempo, com o mesmo resultado |
| `@Version` (otimista) | Sob centenas de requisições na mesma linha, quase toda transação falharia e precisaria de retry; otimismo é a premissa errada aqui |
| `SERIALIZABLE` | Transformaria contenção em `serialization_failure` e exigiria retry em toda a aplicação, para garantir algo que o `UPDATE` condicional já garante |
| Fila serializando as vendas | Acrescenta infraestrutura, latência e um novo modo de falha para resolver o que uma linha de SQL resolve |
| Lock distribuído (Redis) | Outro sistema para operar, e um lock que pode expirar no meio da operação; o PostgreSQL já é o árbitro natural |

## Consequências

- ✅ A correção não depende do número de instâncias, nem de coordenação entre elas, nem de
  estado em memória.
- ✅ Cabe em uma frase de code review: *"o banco incrementa só se couber, e eu confiro
  quantas linhas mudaram"*.
- ✅ Sem retry, sem backoff, sem lock a gerenciar no lado da aplicação.
- ⚠️ Toda venda de um evento disputa a mesma linha: o teto de throughput por evento é
  `1 / latência_de_commit`. Conhecido, medido e aceito — o caminho de evolução é particionar
  o inventário em *buckets*, sem mudar a invariante.
- ⚠️ O SQL fica explícito no repositório, fora do alcance do JPA. É o preço de tornar a
  semântica visível, e aqui ela é a solução, não um detalhe.
- ⚠️ Um `SeatAllocator` mal implementado no futuro reintroduz o defeito. Por isso existe o
  teste que troca a estratégia e **prova** o oversell aparecendo.
