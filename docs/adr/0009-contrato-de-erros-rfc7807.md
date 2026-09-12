# ADR-0009 — Contrato de erros com RFC 7807 e códigos estáveis

- **Status:** Aceita
- **Data:** 2026-09-11

## Contexto

Um sistema de flash sale erra o tempo todo por motivos legítimos: evento esgotado, reserva
já expirada, chave de idempotência reutilizada. O cliente precisa **distinguir** esses casos
para reagir — tentar de novo, desistir, mostrar outra mensagem. Um corpo de erro em texto
livre, ou um formato diferente por endpoint, força o cliente a fazer *parsing* de mensagem,
que quebra na primeira vez que alguém melhora a redação.

## Decisão

Todas as respostas de erro seguem a **RFC 7807** (`application/problem+json`), produzidas em
um único `@RestControllerAdvice`, com duas extensões: `code` — identificador estável,
pensado para máquina — e `timestamp`. Erros de validação trazem ainda `errors[]`, com campo
e motivo.

Quatro categorias, e a regra para escolher entre elas:

| Categoria | Status | Significado |
|---|---|---|
| Validação | `400` | O request não satisfaz o contrato |
| Não encontrado | `404` | O recurso não existe |
| Regra de negócio | `409` | O request é válido, mas o estado do sistema não permite |
| Inesperado | `500` | Defeito — registrado no log, nunca detalhado ao cliente |

A tradução de exceção para status vive no handler, não na exceção: o domínio não importa
`HttpStatus`. Mensagem de exceção interna e stack trace nunca chegam ao corpo da resposta.

## Consequências

- ✅ O cliente programa contra `code`, não contra texto; a mensagem pode mudar ou ser
  traduzida sem quebrar integração.
- ✅ Um único lugar decide como falha vira resposta — controllers ficam sem `try/catch`.
- ✅ Novas regras de negócio herdam de `DomainException` e já saem corretas, sem tocar no
  handler.
- ⚠️ Cada novo código de erro precisa ser documentado no catálogo do README, sob risco de o
  contrato virar folclore.
- ⚠️ Um handler genérico de `Exception` captura também as exceções do próprio Spring MVC e as
  achata em 500. Por isso o advice estende `ResponseEntityExceptionHandler`, que trata a
  lista completa dessas exceções com o status correto — a alternativa, enumerá-las à mão, é
  uma lista que envelhece mal. Há testes de regressão para 404, 405 e 415.
- ⚠️ `409` para toda regra de negócio é uma simplificação consciente; se algum caso exigir
  semântica diferente (`422`, `410`), o mapeamento por tipo de exceção acomoda sem
  reescrever o handler.
