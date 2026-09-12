# ADR-0002 — Java 21 + Spring Boot 4.1.1

- **Status:** Aceita
- **Data:** 2026-09-11

## Contexto

O desafio fixa Java 21 e Spring Boot. O blueprint arquitetural inicial mencionava
"Spring Boot 3.x", escrito antes da definição da stack final. Na data de início, o
Initializr oferece apenas a linha 4.x (4.1.1 como release estável); a linha 3.5 segue
suportada, mas em fim de ciclo.

## Decisão

Java 21 (LTS) e **Spring Boot 4.1.1**, com Spring MVC (stack servlet, não reativa).

Esta é uma **mudança consciente em relação ao "3.x" do blueprint**, e o motivo é: (a) 4.1.1
é a versão estável corrente e a única gerada pelo Initializr hoje; (b) as dependências
previstas para as fases seguintes têm artefato compatível — notadamente
`resilience4j-spring-boot4` 2.4.0; (c) Java 21 é baseline suportado pela linha 4.x.

Spring MVC e não WebFlux: o gargalo do sistema é o commit no banco, não threads de I/O
ociosas. Threads virtuais do Java 21 cobrem o ganho de concorrência sem o custo cognitivo
do modelo reativo.

## Consequências

- ✅ Stack atual, com suporte longo e sem dívida de migração imediata.
- ✅ Código de teste e de produção alinhado à documentação corrente do Spring.
- ⚠️ Spring Boot 4 moveu pacotes e modularizou starters (por exemplo,
  `spring-boot-starter-webmvc` e `AutoConfigureMockMvc` em
  `org.springframework.boot.webmvc.test.autoconfigure`). Exemplos de internet escritos para
  Boot 3 podem não compilar sem ajuste.
- ⚠️ Ecossistema de terceiros ainda em transição para Boot 4; cada dependência nova deve ter
  a compatibilidade verificada antes de entrar.
