# ADR-0006 — JPA/Hibernate para CRUD e Flyway para o schema

- **Status:** Aceita
- **Data:** 2026-09-11

## Contexto

O domínio precisa de persistência relacional em PostgreSQL. Duas perguntas independentes:
como mapear objetos para tabelas, e quem é dono do schema. A tentação de deixar o Hibernate
criar as tabelas (`ddl-auto=update`) economiza minutos no primeiro dia e cobra caro em todos
os outros: schema não versionado, não revisável e diferente entre ambientes.

## Decisão

**Spring Data JPA/Hibernate** para o CRUD do domínio, e **Flyway** como único dono do schema,
com `ddl-auto=validate` em todos os perfis — inclusive nos testes.

O caminho crítico de concorrência não passa pelo JPA: a decisão de vender será um `UPDATE`
condicional em SQL explícito. JPA para conveniência; SQL onde a semântica exata do comando é
a própria solução.

Migrations são imutáveis: uma versão já aplicada nunca é editada, corrige-se com a próxima.
Constraints de integridade vivem na migration, não apenas na entidade.

## Consequências

- ✅ O schema é revisável em pull request e idêntico em teste, desenvolvimento e produção.
- ✅ `validate` transforma divergência entre entidade e tabela em falha na subida, não em
  erro de runtime meses depois.
- ✅ Constraints no banco protegem a invariante mesmo contra escrita que contorne a aplicação.
- ⚠️ Toda mudança de modelo exige escrever a migration à mão — deliberado.
- ⚠️ JPA esconde o SQL gerado; onde isso importa (venda, expiração), o SQL será explícito.
