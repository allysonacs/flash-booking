# ADR-0003 — Gradle Wrapper com Kotlin DSL e toolchain Java 21

- **Status:** Aceita
- **Data:** 2026-09-11

## Contexto

O desafio exige Gradle Wrapper. O projeto será executado por um avaliador cuja máquina tem
um JDK qualquer instalado — possivelmente não o 21. Um build que só funciona na máquina de
quem o escreveu é um defeito de entrega.

## Decisão

Gradle Wrapper 9.7.1 com **Kotlin DSL** (`build.gradle.kts`), declarando uma *toolchain*
Java 21, e o plugin `foojay-resolver-convention` em `settings.gradle.kts` para provisionar
o JDK 21 automaticamente quando ele não existir na máquina.

## Consequências

- ✅ `./gradlew build` funciona sem instalar Gradle e sem exigir que o JDK padrão seja o 21.
- ✅ A versão de Java usada na compilação é a mesma em qualquer máquina e na CI.
- ✅ Kotlin DSL dá tipagem e autocomplete no script de build.
- ⚠️ O primeiro build baixa a distribuição do Gradle (e, se necessário, o JDK 21), exigindo
  rede e alguns minutos.
- ⚠️ Quem só conhece Groovy DSL paga um pequeno custo de sintaxe ao ler o build.
