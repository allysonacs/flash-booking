# ADR-0011 — Idempotência por constraint única, resolvida no banco

- **Status:** Aceita
- **Data:** 2026-09-11

## Contexto

Numa flash sale, o cliente reenvia. O botão é clicado duas vezes, a rede cai depois do
commit e antes da resposta, o app móvel repete a requisição. Sem idempotência, cada
retentativa vira uma reserva a mais — e o cliente paga duas vezes por um assento que pediu
uma vez.

Verificar "já existe uma reserva com essa chave?" antes de inserir não resolve: entre a
consulta e o `INSERT` cabe outra requisição idêntica, possivelmente em outra instância da
API. Cache em memória resolve menos ainda, porque as duas requisições podem nunca tocar a
mesma JVM.

## Decisão

O cliente envia `Idempotency-Key` no header. A unicidade é garantida por um **índice único
parcial** em `(event_id, idempotency_key)` — no banco, que é o único ponto que todas as
instâncias compartilham.

O fluxo tem três caminhos, e o terceiro é o que importa:

1. **Chave já conhecida** → devolve a reserva original, com `200 OK` em vez de `201 Created`.
2. **Chave nova** → cria a reserva; o índice único é a garantia de que ela é a única.
3. **Chave nova em duas requisições simultâneas** → as duas consultam, nada encontram, e as
   duas inserem. O PostgreSQL faz a segunda **aguardar no índice** até a primeira terminar, e
   só então lança a violação. Quando ela é capturada, a vencedora já está commitada e
   visível: a perdedora simplesmente devolve a reserva da vencedora, e seu próprio rollback
   libera os assentos que havia comprometido.

Chave repetida com payload diferente é `409`, não replay: é outra intenção reusando a chave,
e devolver a reserva antiga esconderia do cliente que seu pedido não foi atendido.

Como uma violação de constraint aborta a transação no PostgreSQL, quem captura a violação
precisa estar fora dela — daí a separação entre `ReservationService` (orquestra, sem
transação) e `ReservationTxService` (a transação em si).

## Consequências

- ✅ Funciona entre instâncias por construção: a corrida é decidida onde o estado é único.
- ✅ Nenhuma janela de tempo: a espera no índice único é o que sincroniza as duas requisições.
- ✅ A chave é escopada ao evento, então o cliente pode reaproveitar seus próprios
  identificadores entre eventos diferentes.
- ⚠️ O caminho de corrida custa um rollback e uma leitura extra. É o caminho raro, e o preço
  de não ter nem lock nem coordenação.
- ⚠️ A comparação de payload hoje é a quantidade, porque o payload tem um campo só. Quando o
  contrato crescer, entra um hash do payload canônico no lugar — sem mudar a semântica.
- ⚠️ Chaves ficam guardadas junto da reserva, então a retenção segue a da própria reserva;
  não há expurgo separado a operar.
