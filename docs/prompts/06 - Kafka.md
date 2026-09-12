Continue o projeto existente.

Agora vamos adicionar comunicação assíncrona utilizando Apache Kafka.

## DOCKER COMPOSE

Adicionar Kafka ao docker-compose.yml.

Utilizar uma imagem oficial/estável adequada.

Adicionar Kafka UI para facilitar inspeção dos tópicos durante desenvolvimento.

Todos os componentes devem subir via Docker Compose.

A aplicação Java deve conseguir publicar e consumir eventos.

## EVENTO

Criar evento de domínio/integration event:

ReservationCreated

O evento deve representar a criação bem-sucedida de uma reserva.

Não publicar evento antes da transação principal estar confirmada.

Analise cuidadosamente o problema de:

Database transaction + Kafka publish.

Não implemente uma solução inconsistente apenas com:

repository.save();
kafkaTemplate.send();

Explique o risco.

Implemente uma solução simples e adequada ao desafio.

Considere Outbox Pattern como solução.

Se optar por Outbox:

* criar tabela outbox;
* salvar entidade + evento na mesma transação;
* publisher assíncrono publica no Kafka;
* marcar evento como publicado;
* permitir retry;
* evitar perda do evento.

Não transforme isso em uma implementação exageradamente complexa.

## CONSUMER

Criar um consumer para ReservationCreated.

O consumer deve ser idempotente.

Considere que Kafka pode entregar uma mensagem mais de uma vez.

Não confiar em exactly-once como justificativa para ignorar idempotência no consumidor.

## DISPONIBILIDADE

O requisito diz:

"consistência eventual para disponibilidade."

Documente onde a consistência eventual entra no sistema.

A fonte de verdade para impedir overselling continua sendo o PostgreSQL/transação da reserva.

Kafka NÃO pode ser a fonte de verdade para disponibilidade.

## TESTES

Adicionar testes para:

* publicação;
* consumo;
* retry;
* mensagem duplicada;
* processamento idempotente;
* falha temporária do consumer.

Quando possível, utilizar Testcontainers para Kafka.

## DOCUMENTAÇÃO

Atualizar ARCHITECTURE.md explicando:

* por que Kafka;
* tópico;
* producer;
* consumer;
* consumer group;
* particionamento;
* ordering;
* idempotência;
* eventual consistency;
* Outbox;
* trade-offs.

Também documentar:

"Kafka is not used to guarantee inventory correctness."

Essa distinção deve ficar extremamente clara.

Executar:

./gradlew clean test
