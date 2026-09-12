Você é um Frontend Engineer Senior/Especialista, com forte experiência em React, TypeScript, UX/UI e integração com APIs REST.

O projeto atual é um desafio técnico de Backend para uma posição de Engenheiro de Software Sênior / Especialista.

O backend já foi desenvolvido.

Agora precisamos criar um FRONTEND funcional para demonstrar o sistema visualmente durante a apresentação e code review.

# OBJETIVO

Criar um frontend em React para o sistema:

"Flash Booking — Reserva de Ingressos"

O frontend deve consumir REALMENTE a API backend existente.

Não crie mock da API quando o backend estiver disponível.

O frontend deve permitir demonstrar os principais fluxos do desafio técnico.

---

# REFERÊNCIA VISUAL

Na raiz do projeto existe o arquivo:

CieloTela.png

Essa imagem é a referência visual principal do frontend.

IMPORTANTE:

Antes de implementar qualquer componente visual:

1. Leia o arquivo CieloTela.png.
2. Analise cuidadosamente o design.
3. Identifique:

    * cores;
    * tipografia;
    * tamanhos de fonte;
    * pesos;
    * espaçamentos;
    * bordas;
    * border-radius;
    * sombras;
    * botões;
    * cards;
    * inputs;
    * tabelas;
    * ícones;
    * cabeçalho;
    * navegação;
    * background;
    * alinhamentos;
    * hierarquia visual;
    * responsividade.
4. Utilize a imagem como referência visual.
5. Reproduza o estilo de forma consistente.

NÃO faça simplesmente um frontend genérico de React.

O objetivo é que, olhando para o frontend, seja claramente perceptível que ele foi baseado no design de CieloTela.png.

Não invente uma identidade visual completamente diferente.

---

# TECNOLOGIA

Utilizar:

* React
* TypeScript
* Vite
* CSS ou uma solução simples e adequada para estilização

Priorize simplicidade.

Não introduza uma quantidade excessiva de bibliotecas.

Se uma biblioteca de componentes for realmente necessária, explique antes de adicioná-la.

---

# LOCAL DO FRONTEND

O backend já existe.

Não destrua nem reorganize o backend.

Crie o frontend de maneira organizada.

Uma estrutura aceitável seria:

frontend/
src/
components/
pages/
services/
hooks/
types/
utils/
styles/
App.tsx
main.tsx

Adapte a estrutura caso o projeto existente já tenha uma organização melhor.

---

# REGRA MUITO IMPORTANTE

Antes de começar:

1. examine a estrutura atual do projeto;
2. leia README.md;
3. leia ARCHITECTURE.md;
4. leia CODE_REVIEW.md, se existir;
5. identifique como o backend está sendo executado;
6. identifique os endpoints reais;
7. identifique as portas utilizadas;
8. identifique o formato REAL dos requests;
9. identifique o formato REAL dos responses;
10. examine CieloTela.png.

NÃO presuma contratos de API.

Utilize o código existente como fonte da verdade.

---

# FUNCIONALIDADES DO FRONTEND

O frontend deve permitir demonstrar os requisitos principais do desafio.

## 1. Dashboard / Home

Criar uma tela inicial seguindo o design de CieloTela.png.

Deve apresentar de forma clara:

* eventos disponíveis;
* capacidade;
* ingressos disponíveis;
* status;
* quantidade de reservas;
* informações relevantes.

A interface deve ser visualmente limpa.

Não inventar métricas que não existam no backend.

---

# 2. Lista de eventos

Criar uma tela:

Events

Exibir os eventos existentes.

Cada evento deve apresentar:

* nome;
* capacidade total;
* disponibilidade;
* status;
* ação para visualizar/reservar.

Consumir:

GET /events/{id}

Caso seja necessário criar endpoint de listagem para melhorar a UX, NÃO altere o backend automaticamente.

Primeiro avalie se o backend existente já possui mecanismo para listar eventos.

Se não existir, mantenha a interface compatível com os endpoints disponíveis e documente a limitação.

NÃO invente:

GET /events

sem analisar o backend.

---

# 3. Criar evento

Criar formulário para:

POST /events

Campos:

* nome;
* capacidade.

Validar no frontend:

* nome obrigatório;
* capacidade obrigatória;
* capacidade > 0.

Também respeitar as validações existentes no backend.

Mostrar mensagens de erro amigáveis.

Após criação:

* atualizar a interface;
* mostrar confirmação;
* evitar reload desnecessário da página.

---

# 4. Página de detalhes do evento

Criar uma página visualmente forte para demonstrar o conceito de Flash Booking.

Exibir:

EVENT NAME

Available Tickets

Total Capacity

Status

Reservation controls

Utilizar uma representação visual da disponibilidade.

Por exemplo:

100 / 500 tickets available

com progress bar ou componente equivalente.

A aparência deve seguir CieloTela.png.

---

# 5. Reserva

Criar interface:

"Reserve Tickets"

Permitir escolher quantidade.

Enviar:

POST /events/{id}/reservations

IMPORTANTE:

O frontend deve gerar uma chave de idempotência por tentativa lógica de reserva.

Utilizar:

Idempotency-Key

Não gerar uma nova chave simplesmente porque o usuário clicou novamente enquanto a mesma operação está sendo processada.

O comportamento deve ser coerente com a implementação de idempotência existente no backend.

---

# 6. Feedback de reserva

Após reservar:

mostrar claramente:

* reservation ID;
* quantidade;
* evento;
* status;
* expiration, quando disponível;
* mensagem de sucesso.

Criar uma tela/modal/card de confirmação.

Se a API retornar PENDING:

mostrar claramente:

PENDING

Se retornar outro status:

utilizar o status real da API.

Não inventar estados.

---

# 7. Consulta de reserva

Criar tela:

"Reservation Details"

Utilizar:

GET /reservations/{id}

Exibir:

* reservation ID;
* event;
* quantity;
* status;
* createdAt;
* expiresAt;
* demais informações retornadas pela API.

---

# 8. Cancelamento

Permitir cancelar uma reserva.

Utilizar:

DELETE /reservations/{id}

Antes do cancelamento:

mostrar confirmação.

Depois:

atualizar o estado visual.

Tratar corretamente:

* sucesso;
* reservation not found;
* reservation já cancelada;
* reservation expirada;
* erros inesperados.

---

# 9. EXPIRAÇÃO

Como o backend possui expiração automática:

o frontend deve refletir essa característica.

Se expiresAt estiver disponível:

mostrar um countdown visual.

Exemplo:

Reservation expires in:

04:32

Quando chegar a zero:

não assumir automaticamente que a API processou a expiração.

Consultar/atualizar o estado real através da API.

A API é a fonte da verdade.

---

# 10. Erros

Criar tratamento global de erros de API.

A interface deve tratar pelo menos:

400
404
409
422, se utilizado pelo backend
500
timeout/network error

Especialmente:

INSUFFICIENT CAPACITY

deve gerar uma mensagem clara para o usuário.

Exemplo conceitual:

"Not enough tickets available for this event."

Não mostrar stack trace.

Não mostrar mensagens técnicas desnecessárias ao usuário.

---

# 11. FLASH SALE / CONCORRÊNCIA

A UI deve permitir demonstrar o comportamento do backend sob alta concorrência.

Criar uma área visual que mostre:

Available tickets

e atualize após uma reserva.

Se houver conflito de concorrência:

mostrar uma mensagem adequada.

O frontend NÃO deve tentar controlar overselling.

Essa responsabilidade pertence ao backend.

O frontend apenas apresenta o estado retornado pela API.

---

# 12. LOADING STATES

Todas as chamadas assíncronas devem possuir estados de loading.

Exemplos:

Creating event...

Loading event...

Reserving tickets...

Cancelling reservation...

Não deixar botões aparentemente disponíveis enquanto uma operação está em andamento.

Evitar double-click que gere múltiplas operações acidentais.

---

# 13. EMPTY STATES

Criar estados para:

* nenhum evento;
* reserva inexistente;
* indisponibilidade;
* erro de carregamento.

Não deixar áreas vazias sem explicação.

---

# 14. RESPONSIVIDADE

O frontend deve funcionar em:

* desktop;
* tablet;
* mobile.

Priorizar desktop porque será utilizado na apresentação do desafio, mas não criar layout quebrado em telas menores.

---

# DESIGN SYSTEM

Depois de analisar CieloTela.png, crie tokens reutilizáveis para:

* primary color;
* secondary color;
* background;
* surface;
* text;
* muted text;
* border;
* success;
* warning;
* error;
* spacing;
* radius;
* typography.

Evite valores espalhados pelo código.

Se utilizar CSS variables, ótimo.

Exemplo conceitual:

--color-primary
--color-background
--color-surface
--color-text
--color-error
--radius-md
--spacing-md

Os valores devem ser derivados da imagem de referência.

---

# COMPONENTIZAÇÃO

Não criar um componente gigante.

Separar componentes reutilizáveis.

Por exemplo:

components/
Button
Card
Input
Modal
Loading
ErrorMessage
EventCard
AvailabilityBar
ReservationCard
StatusBadge

Adapte conforme necessário.

Não criar componentes apenas por criar.

---

# API CLIENT

Criar uma camada centralizada para comunicação com o backend.

Por exemplo:

services/api.ts

ou estrutura equivalente.

Não espalhar:

fetch(...)
axios(...)
headers

por todos os componentes.

Centralizar:

* base URL;
* headers;
* tratamento de erro;
* Idempotency-Key;
* timeout quando apropriado.

A URL da API deve ser configurável por variável de ambiente.

Exemplo:

VITE_API_BASE_URL

Não hardcode localhost em vários arquivos.

---

# TIPAGEM

Utilizar TypeScript corretamente.

Criar tipos para os objetos retornados pelo backend.

Exemplo conceitual:

Event
Reservation
CreateEventRequest
CreateReservationRequest
ApiError

Os tipos devem refletir o contrato REAL do backend.

Não usar:

any

sem justificativa.

---

# SEGURANÇA

Não colocar:

* secrets;
* passwords;
* tokens privados;

no frontend.

Tudo que estiver no frontend deve ser considerado público.

---

# UX

O usuário deve entender facilmente:

1. quais eventos existem;
2. quanto está disponível;
3. como reservar;
4. qual reserva foi criada;
5. quando a reserva expira;
6. como cancelar;
7. qual é o estado atual da reserva.

Evite UX excessivamente complexa.

---

# APRESENTAÇÃO DO DESAFIO

O frontend será utilizado durante uma apresentação de code review.

Portanto, priorize uma experiência que permita demonstrar facilmente:

### Fluxo 1

Criar evento

↓

Evento aparece na interface

### Fluxo 2

Selecionar evento

↓

Ver disponibilidade

↓

Reservar ingressos

↓

Receber Reservation ID

### Fluxo 3

Consultar reserva

↓

Ver status

### Fluxo 4

Cancelar reserva

↓

Disponibilidade atualizada

### Fluxo 5

Expiração

↓

Reserva PENDING

↓

Countdown

↓

EXPIRED

↓

Ingressos retornam à disponibilidade

---

# DEMONSTRAÇÃO DE CONCORRÊNCIA

Se for simples de implementar no frontend, criar uma pequena ferramenta de demonstração:

"Flash Sale Simulator"

Onde o usuário possa configurar:

Requests: 10 / 50 / 100

Tickets per request: 1

e disparar requisições concorrentes contra o backend.

IMPORTANTE:

Essa funcionalidade é apenas uma ferramenta de demonstração.

Ela não deve alterar a implementação do mecanismo de reserva.

O backend continua sendo responsável pela consistência.

Se essa funcionalidade exigir alterações significativas ou tornar o projeto excessivamente complexo, NÃO implemente. Nesse caso, documente a decisão.

---

# ACESSIBILIDADE

Aplicar boas práticas:

* labels em inputs;
* botões semanticamente corretos;
* contraste adequado;
* foco;
* navegação básica por teclado;
* aria-label quando realmente necessário.

---

# PERFORMANCE

Evitar:

* chamadas desnecessárias;
* polling agressivo;
* renders desnecessários;
* reload completo da página.

Para atualização de disponibilidade, utilize uma estratégia simples e adequada.

Não introduza WebSocket apenas para parecer sofisticado.

---

# DOCUMENTAÇÃO

Atualizar README.md com:

## Frontend

* stack;
* como instalar;
* como executar;
* variável VITE_API_BASE_URL;
* integração com backend;
* principais telas;
* principais fluxos.

Adicionar uma seção:

## Frontend Architecture

Explicar:

* componentização;
* API client;
* state management;
* tratamento de erros;
* idempotência;
* loading;
* integração com backend.

---

# VALIDAÇÃO FINAL

Depois de implementar:

1. instalar dependências;
2. executar lint, se configurado;
3. executar testes;
4. executar build;
5. iniciar frontend;
6. conectar ao backend;
7. testar os fluxos reais.

Executar:

npm run build

ou o comando equivalente definido pelo projeto.

Não considerar o trabalho concluído se o build estiver quebrado.

---

# REGRA CONTRA ALUCINAÇÃO

Essa regra é extremamente importante.

NÃO invente:

* endpoints;
* campos;
* responses;
* status;
* regras de negócio;
* URLs;
* funcionalidades do backend.

Sempre leia o backend existente antes.

Se o frontend precisar de alguma informação que o backend não fornece:

1. identifique o problema;
2. explique;
3. proponha a alteração mínima necessária;
4. somente implemente a alteração do backend se ela for realmente necessária e compatível com o desafio.

Não reescreva funcionalidades existentes.

---

# REGRA SOBRE O DESIGN

CieloTela.png é a referência visual principal.

Não quero que você simplesmente faça:

"um dashboard moderno".

Quero que você reproduza a linguagem visual da imagem.

Analise primeiro.

Depois implemente.

Mantenha consistência entre todas as telas.

Não copie literalmente elementos que não façam sentido para o sistema de reserva.

Faça uma adaptação do design para o domínio:

Flash Booking.

---

# PROCESSO DE IMPLEMENTAÇÃO

Execute em pequenas etapas.

ETAPA 1:
Analisar projeto e CieloTela.png.

ETAPA 2:
Criar estrutura React.

ETAPA 3:
Criar design system baseado na imagem.

ETAPA 4:
Criar layout principal.

ETAPA 5:
Implementar Events.

ETAPA 6:
Implementar Reservation.

ETAPA 7:
Implementar Reservation Details.

ETAPA 8:
Implementar Cancelamento.

ETAPA 9:
Implementar estados de loading/error/empty.

ETAPA 10:
Integrar countdown/expiration.

ETAPA 11:
Testar integração completa.

ETAPA 12:
Build final.

NÃO pule diretamente para uma implementação gigante.

Após cada etapa importante, valide o funcionamento.

---

# CRITÉRIO FINAL

O resultado deve parecer um produto real e não apenas uma tela criada para satisfazer um teste.

Mas:

Simplicidade > complexidade.

Clareza > quantidade de componentes.

Consistência visual > efeitos.

Integração real > mocks.

Código legível > abstrações excessivas.

O frontend deve complementar o backend e ajudar a demonstrar as decisões de arquitetura durante a apresentação.

Ao final, apresente:

1. estrutura do frontend;
2. telas criadas;
3. componentes principais;
4. integração com cada endpoint;
5. decisões de design;
6. decisões técnicas;
7. comandos para executar;
8. resultado do build;
9. resultado dos testes;
10. qualquer limitação encontrada.
