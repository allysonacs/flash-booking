# ADR-0014 — Transactional Outbox para publicar eventos no Kafka

- **Status:** Aceita
- **Data:** 2026-09-12

## Contexto

A venda precisa produzir um evento de integração (`ReservationCreated`) que outros sistemas
consomem. A forma direta — `repository.save()` seguido de `kafkaTemplate.send()` — é duas
escritas em dois sistemas sem transação comum, e falha nas duas ordens possíveis:

- **commit e depois publicar:** o processo morre no meio, ou o broker está fora, e o evento
  nunca existirá. Não sobra nem registro de que havia algo a publicar, então não há
  retentativa possível;
- **publicar dentro da transação:** o commit falha depois do envio, e o evento anuncia uma
  reserva que o rollback desfez.

XA/2PC resolveria, ao custo de um coordenador e de deixar o commit do PostgreSQL refém da
disponibilidade do Kafka — o oposto do desacoplamento que motivou a mensageria.

## Decisão

**Transactional Outbox.** O evento é gravado em `outbox_messages` na mesma transação que cria
a reserva; um relay agendado publica no Kafka e marca a linha como publicada.

- O relay roda em todas as instâncias e reivindica lotes com `FOR UPDATE SKIP LOCKED` — o
  mesmo mecanismo da expiração (ADR-0012), sem eleição de líder.
- Só é marcado como `PUBLISHED` o que o broker **confirmou** (`acks=all`, envio com espera).
- Uma publicação que falha mantém a linha `PENDING` e incrementa `attempts`: a retentativa é
  o comportamento padrão, e o evento não se perde.
- A chave da mensagem é o id do evento (show), o que mantém as reservas de um mesmo show na
  mesma partição.
- O id da linha do outbox é o `messageId` do payload, e é a chave de deduplicação do
  consumidor (`processed_events`, `INSERT ... ON CONFLICT DO NOTHING`).

**O Kafka não participa da decisão de vender.** Inventário e estado de reserva continuam
sendo decididos pela transação PostgreSQL; o evento é o registro de um fato já commitado.

## Consequências

- ✅ Não existe reserva sem evento, nem evento sem reserva.
- ✅ Kafka indisponível atrasa a integração e **não impede vendas**: o outbox acumula e drena
  quando o broker volta.
- ✅ A venda não espera pelo broker: a publicação é assíncrona, fora do caminho quente.
- ✅ Auditoria de graça: a tabela guarda o que foi produzido, com tentativas e último erro.
- ⚠️ A entrega é *at-least-once*. Publicar e marcar como publicado continuam sendo duas
  escritas em dois sistemas; o outbox troca a **perda** pela **duplicata**, e a duplicata é
  resolvida por chave primária no consumidor. Exactly-once do broker não cobre efeitos que
  saem do Kafka.
- ⚠️ Uma escrita a mais na transação da venda, em tabela sem contenção.
- ⚠️ O outbox cresce e precisa de limpeza periódica das linhas publicadas.
- ⚠️ Com várias instâncias no relay, a ordem entre mensagens do mesmo show é aproximada.
  Aceitável porque nenhum consumidor atual depende de ordem; garantir ordem estrita exigiria
  serializar o relay por chave.
