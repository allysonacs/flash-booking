Continue o projeto existente.

Agora vamos adicionar mecanismos de resiliência.

IMPORTANTE:

Não adicionar resiliência artificial onde não existe chamada externa.

Identifique primeiro quais componentes representam dependências externas ou operações que justificam:

* timeout;
* retry;
* circuit breaker.

Não utilize circuit breaker diretamente em operações locais de PostgreSQL apenas para "mostrar conhecimento".

Crie uma pequena integração externa simulável se necessário, mas mantenha o domínio principal simples.

Utilize Resilience4j.

## REQUISITOS

Implementar, quando fizer sentido:

* timeout;
* retry;
* circuit breaker.

Configurações externalizadas.

Nunca hardcode valores diretamente nas classes.

## TIMEOUT

Defina timeout explícito para chamadas externas.

Explique:

* por que existe;
* valor escolhido;
* comportamento quando ocorre timeout.

## RETRY

Retry somente para operações consideradas seguras/transitórias.

Não aplicar retry cego em operações de reserva que possam gerar duplicidade.

Documentar:

* quais erros podem ser retryable;
* quais não podem;
* quantidade máxima de tentativas;
* backoff.

## CIRCUIT BREAKER

Implementar circuit breaker para a dependência externa escolhida.

Documentar:

* CLOSED;
* OPEN;
* HALF_OPEN.

Explicar o que acontece quando o circuito abre.

## TESTES

Criar testes demonstrando:

* timeout;
* retry;
* circuit breaker abrindo;
* recuperação;
* comportamento de fallback quando apropriado.

Não mascarar erros de negócio.

## ENTREVISTA

Atualizar ARCHITECTURE.md com uma seção:

"Resilience Strategy"

Explicar de forma objetiva:

* timeout;
* retry;
* circuit breaker;
* idempotência;
* quando NÃO utilizar retry.

Execute:

./gradlew clean test
