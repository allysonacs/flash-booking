# ADR-0013 — Resiliência apenas na borda externa

- **Status:** Aceita
- **Data:** 2026-09-12

## Contexto

O sistema precisa de mecanismos de resiliência. A tentação é distribuí-los por toda parte —
retry no repositório, circuit breaker na frente do PostgreSQL, timeout em cada método — e o
resultado costuma ser pior do que não ter nenhum: um retry sobre uma escrita não idempotente
duplica reservas, e um circuit breaker sobre o banco transforma "o banco caiu", que é
explícito e alarmável, em "não vendemos nada e ninguém sabe por quê".

Até esta fase o sistema não tinha **nenhuma** dependência externa: tudo acontece dentro de
uma transação PostgreSQL. Resiliência não se aplica a uma chamada local; ela existe para a
fronteira onde um processo depende de outro que pode estar fora.

## Decisão

Introduzir **uma** dependência externa real — o serviço de notificação da reserva — e
aplicar Resilience4j **somente** a ela.

A escolha do candidato não é arbitrária. Vale a pena proteger o que satisfaz três
condições, e a notificação satisfaz as três:

1. é uma chamada **remota**, que pode demorar, falhar ou sumir;
2. sua falha **não invalida** a operação principal — a reserva já está commitada;
3. a operação é **idempotente na origem**, identificada pelo id da reserva, o que torna a
   retentativa segura.

Mecanismos, nesta ordem de importância:

| Mecanismo | Onde | Por quê |
|---|---|---|
| **Timeout** (connect + read) | cliente HTTP | Sem ele, nada mais funciona: a thread não volta para ser protegida |
| **Retry** com backoff exponencial e jitter | `@Retry` | Cobre a falha transitória, e só ela |
| **Circuit breaker** | `@CircuitBreaker` | Cobre a falha persistente: para de bater em quem já está caído |
| **Fallback** | método do gateway | Descarta o aviso com log; a venda segue válida |
| **Isolamento do caminho da venda** | chamada feita por um consumidor Kafka (ADR-0014) | A latência externa não toca as threads que atendem requisições |

Explicitamente **não** adotados: retry ou circuit breaker em operações do PostgreSQL,
time limiter sobre chamada bloqueante (o timeout do socket é o que de fato interrompe) e
rate limiter (não há cota a respeitar).

O `fallbackMethod` fica declarado no `@Retry`, o aspecto mais externo. No aspecto interno
ele devolveria normalmente e o retry nunca repetiria nada.

## Consequências

- ✅ A resiliência está onde há risco real, e o `build.gradle.kts` continua descrevendo com
  honestidade o que o sistema faz.
- ✅ Todos os parâmetros — timeouts, tentativas, backoff, janela do circuito — são
  configuração, sobrescrevíveis por variável de ambiente e por teste.
- ✅ Uma falha do serviço de notificação não aparece para quem compra, e uma falha de negócio
  continua aparecendo inteira.
- ⚠️ Notificação é *best-effort*: com o serviço fora por tempo suficiente, avisos são
  perdidos e ficam apenas no log. O outbox (ADR-0014) garante que o *evento* nunca se perde;
  a entrega final ao serviço externo continua sujeita a ele estar de pé.
- ⚠️ Retry multiplica carga sobre um serviço que já está mal; o teto de 3 tentativas, o
  backoff exponencial e o circuit breaker existem para limitar esse efeito.
- ⚠️ Mais uma dependência (`resilience4j-spring-boot3`) e mais um ponto de configuração.
