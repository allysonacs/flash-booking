# ADR-0001 — Arquitetura MVC em camadas

- **Status:** Aceita
- **Data:** 2026-09-11

## Contexto

O domínio tem dois agregados (`Event` e `Reservation`) e cinco endpoints. A dificuldade do
problema não está na modelagem do domínio, e sim na **concorrência**: garantir que N
instâncias simultâneas nunca vendam mais assentos do que existem. O projeto também precisa
ser lido e compreendido por um avaliador em poucos minutos.

## Decisão

Adotar **MVC em camadas clássicas** (`controller` → `service` → `repository`), com
dependências apontando sempre para dentro, DTOs separados das entidades e regra de negócio
concentrada no serviço. Não adotar Clean Architecture, Hexagonal nem DDD tático elaborado.

Interfaces são criadas apenas onde existe ponto de variação real — o caso concreto previsto
é o `SeatAllocator`, cuja implementação alternativa e ingênua serve para *provar*, em teste,
que a estratégia anti-oversell é o que impede o oversell.

## Consequências

- ✅ Menos indireção: o caminho de uma requisição até o SQL é legível em três saltos.
- ✅ O esforço de engenharia fica onde está a dificuldade real (concorrência), não na
  cerimônia de camadas.
- ⚠️ O domínio fica acoplado ao Spring e ao JPA — aceitável: não há intenção de trocar de
  framework nem de reaproveitar o domínio fora desta aplicação.
- ⚠️ Se o domínio crescer muito além do escopo do desafio, essa decisão deve ser revista
  com um novo ADR.
