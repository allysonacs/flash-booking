# ADR-0004 — PostgreSQL como único ponto de coordenação

- **Status:** Aceita (direcional — implementação a partir da Fase 1)
- **Data:** 2026-09-11

## Contexto

Com múltiplas instâncias da API, alguém precisa arbitrar quem fica com o último assento. As
alternativas usuais são lock distribuído (Redis/ZooKeeper), fila serializando as vendas, ou
delegar a arbitragem ao próprio banco transacional.

## Decisão

Toda coordenação entre instâncias acontece no **PostgreSQL**. Nenhum estado de negócio em
memória, nenhum lock distribuído, nenhum componente extra de infraestrutura. A decisão de
vender é um `UPDATE` condicional atômico sobre a linha de inventário, protegido por uma
`CHECK` constraint como rede de segurança. O agendamento distribuído usa
`FOR UPDATE SKIP LOCKED`, e a integração com Kafka usa a tabela de outbox — ambos no mesmo
banco, no mesmo commit.

## Consequências

- ✅ Correção independe do número de instâncias e não depende de nenhum componente extra
  estar saudável.
- ✅ Um único mecanismo transacional explica overselling, expiração e publicação de eventos.
- ✅ Menos infraestrutura para subir, operar e explicar.
- ⚠️ O banco vira o gargalo e o ponto único de falha do caminho de venda — conhecido,
  declarado e aceito para este escopo (ver ARCHITECTURE.md, seção de riscos).
- ⚠️ A contenção na linha de inventário limita o throughput **por evento**; a evolução
  prevista, se necessária, é particionar o inventário em *buckets*.
