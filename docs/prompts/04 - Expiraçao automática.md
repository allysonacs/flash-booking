Continue o projeto existente.

Implemente agora a expiração automática de reservas PENDING.

## REGRA

Uma reserva PENDING possui:

expiresAt.

Quando ultrapassar expiresAt:

PENDING → EXPIRED

e os ingressos devem retornar para a disponibilidade do evento.

## IMPORTANTE

A expiração precisa funcionar com múltiplas instâncias da aplicação.

Não utilize memória local como fonte de verdade.

Não dependa de uma única instância executando o job.

## IMPLEMENTAÇÃO

Avalie uma solução simples utilizando:

Spring Scheduler

com controle transacional e lock no banco.

A solução deve evitar que duas instâncias processem a mesma reserva simultaneamente.

Analise:

* SELECT FOR UPDATE;
* status transition;
* transação;
* idempotência do processamento.

Não introduza Redis apenas para resolver esse problema.

Não complique desnecessariamente.

## CONCORRÊNCIA

Uma reserva não pode ser:

CANCELLED

e simultaneamente:

EXPIRED

de maneira inconsistente.

Defina claramente quem vence em caso de concorrência.

O banco deve garantir a consistência.

## TESTES

Criar testes para:

* reserva expirar;
* ingressos retornarem;
* reserva já cancelada não expirar;
* reserva já expirada não ser processada novamente;
* múltiplos workers/threads tentando expirar a mesma reserva;
* disponibilidade nunca ultrapassar a capacidade total.

## DOCUMENTAÇÃO

Atualizar ARCHITECTURE.md explicando:

* estratégia do scheduler;
* estratégia de locking;
* comportamento em múltiplas instâncias;
* transições de estado;
* idempotência do processo.

Não implementar ainda Kafka ou Resilience4j.

Executar:

./gradlew clean test
