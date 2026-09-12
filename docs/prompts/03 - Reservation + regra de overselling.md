Continue o projeto existente.

Antes de alterar o código:

1. leia ARCHITECTURE.md;
2. leia README.md;
3. leia ADRs;
4. examine Event;
5. examine EventRepository;
6. examine os testes existentes.

Agora precisamos implementar o núcleo do desafio:

RESERVA DE INGRESSOS EM FLASH SALE.

## ENDPOINT

Implementar:

POST /events/{id}/reservations

GET /reservations/{id}

DELETE /reservations/{id}

## IMPORTANTE

Antes de escrever código, ANALISE o problema de concorrência.

O sistema terá múltiplas instâncias da API acessando o mesmo PostgreSQL.

Precisamos garantir:

NUNCA permitir overselling.

Não pode existir uma implementação baseada apenas em:

event.getAvailableCapacity();
event.setAvailableCapacity(...);
repository.save(event);

pois isso pode gerar race condition.

Explique primeiro qual mecanismo de concorrência será utilizado.

Considere alternativas como:

* pessimistic locking;
* optimistic locking;
* atomic update;
* database constraint;
* transação;
* combinação dessas estratégias.

Escolha a solução que melhor equilibre:

* segurança;
* simplicidade;
* performance;
* facilidade de explicar em code review.

A implementação deve continuar simples MVC.

## REQUISITO FUNDAMENTAL

Para um evento com capacidade 100:

100 ingressos podem ser reservados.

A 101ª tentativa deve falhar.

Mesmo que:

* 10;
* 100;
* 1.000;
* ou várias instâncias da API

tentem reservar simultaneamente.

Não pode existir oversell.

## RESERVATION

Criar entidade Reservation.

Considere campos como:

* id;
* eventId;
* quantity;
* status;
* createdAt;
* expiresAt;
* idempotencyKey.

Defina estados claros.

Por exemplo:

PENDING
CONFIRMED
CANCELLED
EXPIRED

Não adicione estados sem necessidade.

## TRANSAÇÃO

A criação da reserva e atualização da disponibilidade precisam possuir consistência transacional.

Analise cuidadosamente:

@Transactional

e o comportamento da transação no PostgreSQL.

## CONCORRÊNCIA

Crie testes de concorrência reais.

Não basta testar:

request A
request B

sequencialmente.

Crie um teste onde múltiplas threads tentam reservar simultaneamente.

Exemplo:

evento com capacidade 100.

100 requests tentando reservar 1.

Resultado esperado:

100 reservas bem-sucedidas.

Nenhuma quantidade negativa.

Depois:

evento com capacidade 100.

200 requests tentando reservar 1.

Resultado:

100 reservas bem-sucedidas.

100 falhas.

Nunca:

availableCapacity < 0

e nunca:

successfulReservations > capacity.

## IDEMPOTÊNCIA

Nesta etapa já queremos preparar idempotência.

O endpoint de reserva deverá aceitar:

Idempotency-Key

Duas requisições com a mesma chave não podem criar duas reservas.

A segunda requisição deve retornar o mesmo resultado lógico da primeira.

A implementação precisa funcionar com múltiplas instâncias.

Portanto, NÃO implemente idempotência somente em memória.

A chave deve ser persistida.

Considere uma constraint UNIQUE no banco.

Analise race conditions envolvendo duas requisições simultâneas com a mesma Idempotency-Key.

## CANCELAMENTO

DELETE /reservations/{id}

Deve cancelar uma reserva válida.

Ao cancelar, a quantidade de ingressos deve voltar para a disponibilidade.

Não permitir cancelamento inválido.

Analise idempotência do DELETE.

## ERROS

Defina exceções específicas.

Por exemplo:

EventNotFoundException
ReservationNotFoundException
InsufficientCapacityException
InvalidReservationStateException
DuplicateIdempotencyKeyException

Os nomes podem ser adaptados se houver solução melhor.

## TESTES

Obrigatório criar:

1. testes unitários;
2. testes de controller;
3. testes de integração;
4. testes de concorrência;
5. testes de idempotência;
6. testes de cancelamento.

Essa documentação deve ser suficientemente clara para eu explicar em uma entrevista de Senior/Especialista.

IMPORTANTE:

Não implemente ainda:

* Kafka;
* SQS;
* SNS;
* LocalStack;
* circuit breaker;
* retry;
* expiração automática.

Esses itens virão depois.

Execute:

./gradlew clean test

e só finalize quando os testes passarem.
