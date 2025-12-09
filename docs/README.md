# Chat4All - Sistema de Mensagens Distribuído

Sistema de mensagens distribuído com **gRPC**, **Kafka**, **Cassandra**, **Redis** para demonstração de arquitetura de sistemas distribuídos.

---

## 🎯 Visão Geral do Projeto

**Chat4All** é uma plataforma educacional de mensagens que demonstra conceitos fundamentais de sistemas distribuídos:

- **Arquitetura Event-Driven** - Processamento assíncrono com Kafka
- **Persistência Distribuída** - Cassandra com particionamento por `conversation_id`
- **API de Alta Performance** - gRPC com Protocol Buffers  
- **Autenticação JWT** - Tokens com chave estática para demonstração
- **Notificações em Tempo Real** - WebSocket com Redis Pub/Sub
- **Escalabilidade Horizontal** - Serviços stateless prontos para scale-out

### 🔧 Stack Tecnológica

- **Backend:** Java 17, gRPC
- **Message Broker:** Apache Kafka (particionamento por `conversation_id`)
- **Banco de Dados:** Cassandra (NoSQL distribuído)
- **Cache/Pub-Sub:** Redis
- **Gateway Real-Time:** WebSocket (Netty)
- **Monitoramento:** Prometheus + Grafana
- **Containers:** Docker Compose

---

## 📋 Requisitos Atendidos

### ✅ API Básica com Endpoints

**Implementado via gRPC (equivalente REST):**

#### POST /v1/messages
- **Endpoint gRPC:** `MessageService/SendMessage`
- **Funcionalidade:** Enviar mensagem de texto
- **Autenticação:** JWT Bearer token obrigatório
- **Request:**
  ```protobuf
  message SendMessageRequest {
    string conversation_id = 1;
    string content = 2;
    string recipient_id = 3;
  }
  ```
- **Response:**
  ```protobuf
  message SendMessageResponse {
    string message_id = 1;
    string status = 2;  // "ACCEPTED"
    int64 timestamp = 3;
  }
  ```
- **Código:** `api-service/src/main/java/chat4all/api/grpc/service/MessageServiceImpl.java`

#### GET /v1/conversations/{id}/messages
- **Endpoint gRPC:** `MessageService/GetMessages`
- **Funcionalidade:** Listar mensagens de uma conversa
- **Autenticação:** JWT Bearer token obrigatório
- **Request:**
  ```protobuf
  message GetMessagesRequest {
    string conversation_id = 1;
    int32 limit = 2;
    int64 offset = 3;
  }
  ```
- **Response:**
  ```protobuf
  message GetMessagesResponse {
    repeated Message messages = 1;
    Pagination pagination = 2;
  }
  ```
- **Código:** `api-service/src/main/java/chat4all/api/grpc/service/MessageServiceImpl.java`

### ✅ Autenticação JWT

- **Implementação:** Tokens JWT com chave estática `chat4all-secret-key`
- **Geração:** `api-service/src/main/java/chat4all/api/auth/TokenGenerator.java`
- **Validação:** `api-service/src/main/java/chat4all/api/grpc/interceptor/AuthInterceptor.java`
- **Endpoints públicos:** `/auth/register`, `/auth/login`
- **Endpoints protegidos:** Todos os demais (SendMessage, GetMessages, etc.)
- **Formato do Token:** Bearer token no header `Authorization`

### ✅ Integração com Kafka

- **Tópico:** `messages` (criado automaticamente)
- **Particionamento:** Por `conversation_id` (garante ordem das mensagens)
- **Produtor:** `api-service/src/main/java/chat4all/api/kafka/MessageProducer.java`
  - Publica mensagens no Kafka ao receber SendMessage
  - Timeout: 5 segundos para confirmação
  - Logging completo de partition/offset
- **Consumidor:** `router-worker/src/main/java/chat4all/worker/kafka/MessageConsumer.java`
  - Consumer Group: `router-worker-group`
  - Manual commit após processamento bem-sucedido
  - Retry automático em caso de falha
- **Configuração:** `docker-compose.yml` (Kafka + Zookeeper)

### ✅ Persistência de Mensagens

- **Banco:** Cassandra (NoSQL distribuído)
- **Schema:** `cassandra-init/schema.cql`
- **Tabela Principal:**
  ```sql
  CREATE TABLE messages (
    conversation_id TEXT,
    timestamp TIMESTAMP,
    message_id TEXT,
    sender_id TEXT,
    content TEXT,
    status TEXT,
    delivered_at TIMESTAMP,
    read_at TIMESTAMP,
    file_id TEXT,
    file_metadata MAP<TEXT, TEXT>,
    PRIMARY KEY (conversation_id, timestamp)
  ) WITH CLUSTERING ORDER BY (timestamp ASC);
  ```
- **Partition Key:** `conversation_id` (distribui mensagens)
- **Clustering Key:** `timestamp` (ordena cronologicamente)
- **Estados:**
  - `SENT` - Mensagem aceita pelo sistema
  - `DELIVERED` - Entregue ao destinatário (simulado)
  - `READ` - Lida pelo destinatário (futuro)
- **Metadados:** Timestamp de criação, sender_id, file_id (para anexos)
- **Implementação:** `api-service/src/main/java/chat4all/api/cassandra/CassandraMessageRepository.java`

### ✅ Worker Simples (router-worker)

- **Serviço:** `router-worker/`
- **Funcionalidades:**
  1. **Consumir do Kafka** - Topic `messages`
  2. **Deduplicação** - Verifica se `message_id` já existe
  3. **Persistir** - Salva com status `SENT`
  4. **Simular Entrega** - Sleep 100ms (latência de rede)
  5. **Atualizar Status** - Marca como `DELIVERED`
  6. **Notificar** - Publica via Redis para WebSocket Gateway
- **Logs de Auditoria:**
  ```
  ▶ Processing message: msg_xxx (conv: direct_user_A_user_B)
  ✓ [1/2] Saved with status=SENT
  ✓ [2/2] Simulated delivery
  ✓ Status updated to DELIVERED
  ✅ Message processed: msg_xxx (duration: 120ms)
  ```
- **Código:** `router-worker/src/main/java/chat4all/worker/processing/MessageProcessor.java`

### ✅ Teste de Comunicação Interna

**Script automático:** `test_e2e_working.sh` ✅ **TESTADO E FUNCIONANDO**

Demonstra:
1. Criar 2 usuários (Alice e Bob)
2. Autenticar ambos (obter JWT tokens)
3. Bob envia mensagem para Alice
4. Verificar persistência no Cassandra
5. Alice recupera as mensagens
6. Mostrar logs do worker processando

**Executar:**
```bash
./test_e2e_working.sh
```

**Saída esperada:**
```
✅ TESTE COMPLETO E APROVADO!
📊 Resumo da Execução:
   • Mensagem: msg_xxx
   • Status: ACCEPTED
   • Mensagens recuperadas: 1
🎯 Status: TODOS OS REQUISITOS ATENDIDOS
```

**⚠️ IMPORTANTE:** Este script foi TESTADO e está FUNCIONANDO. Sempre verifique que os serviços estão rodando antes de executar.

### ✅ Documentação e Versionamento

- **README.md** - Este arquivo com arquitetura e instruções
- **openapi.yaml** - Documentação OpenAPI completa
- **Docker Compose** - Script de inicialização automática
- **Endpoints documentados** - Exemplos de uso com grpcurl
- **Diagramas de arquitetura** - Fluxo de dados completo

### ✅ Script de Inicialização Automática

**Docker Compose:** `docker-compose.yml`

Serviços incluídos:
- ✅ Zookeeper (porta 2181)
- ✅ Kafka (porta 9092)
- ✅ Cassandra (porta 9042) com schema automático
- ✅ Redis (porta 6379)
- ✅ MinIO (porta 9000)
- ✅ API Service (porta 9091)
- ✅ Router Worker
- ✅ WebSocket Gateway (porta 8765)
- ✅ Prometheus (porta 9090)
- ✅ Grafana (porta 3000)

**Iniciar tudo:**
```bash
docker-compose up -d
```

**Verificar status:**
```bash
docker-compose ps
```

---

## 🚀 Início Rápido

### 1. Iniciar o Sistema

```bash
# Clonar repositório (se ainda não tiver)
git clone <repo-url>
cd chat4alltijolim-001-basic-messaging-api

# Iniciar todos os serviços via Docker Compose
docker-compose up -d

# Aguardar inicialização (30-60 segundos)
# Verificar logs
docker-compose logs -f api-service
```

### 2. Executar Teste End-to-End

```bash
# Script TESTADO demonstrando comunicação entre 2 usuários
./test_e2e_working.sh
```

Este script irá:
- ✅ Criar 2 usuários (Alice e Bob)
- ✅ Autenticar ambos (JWT)
- ✅ Enviar mensagem de Bob → Alice
- ✅ Verificar persistência no Cassandra
- ✅ Recuperar mensagens
- ✅ Exibir logs de auditoria do worker

### 3. Testar Manualmente com grpcurl

#### Registrar Usuário

```bash
grpcurl -plaintext -d '{
  "username": "alice",
  "email": "alice@test.com",
  "password": "senha123"
}' localhost:9091 chat4all.AuthService/Register
```

#### Login

```bash
grpcurl -plaintext -d '{
  "username": "alice",
  "password": "senha123"
}' localhost:9091 chat4all.AuthService/Login

# Copie o accessToken da resposta
```

#### Enviar Mensagem (POST /v1/messages)

```bash
TOKEN="<seu_token_aqui>"

grpcurl -plaintext \
  -H "authorization: Bearer $TOKEN" \
  -d '{
    "conversation_id": "direct_user_A_user_B",
    "content": "Olá! Esta é uma mensagem de teste.",
    "recipient_id": "user_B"
  }' localhost:9091 chat4all.MessageService/SendMessage
```

#### Recuperar Mensagens (GET /v1/conversations/{id}/messages)

```bash
grpcurl -plaintext \
  -H "authorization: Bearer $TOKEN" \
  -d '{
    "conversation_id": "direct_user_A_user_B",
    "limit": 50,
    "offset": 0
  }' localhost:9091 chat4all.MessageService/GetMessages
```

---

## 📊 Arquitetura do Sistema

### Fluxo de Dados Completo

```
┌─────────────┐
│   Cliente   │ (grpcurl, Python, aplicação externa)
└──────┬──────┘
       │ gRPC SendMessage (HTTP/2 + Protobuf)
       ├─ Authorization: Bearer <JWT>
       ├─ conversation_id: "direct_user_A_user_B"
       └─ content: "Olá!"
       ▼
┌──────────────────────────────────────┐
│  API Service (Java/gRPC)             │
│  - Valida JWT (AuthInterceptor)      │
│  - Gera message_id                   │
│  - Retorna ACCEPTED                  │
└───────────┬──────────────────────────┘
            │ Kafka Producer
            │ Topic: messages
            │ Partition key: conversation_id
            ▼
┌──────────────────────────────────────┐
│  Apache Kafka                        │
│  - Garantia de ordem por partition   │
│  - Durabilidade (log persistence)    │
└───────────┬──────────────────────────┘
            │ Consumer Group: router-worker-group
            ▼
┌──────────────────────────────────────┐
│  Router Worker (Processor)           │
│  [1] Deduplicação (message_id)       │
│  [2] Persist → Cassandra (SENT)      │
│  [3] Simulate Delivery (sleep 100ms) │
│  [4] Update Status → DELIVERED       │
│  [5] Publish Notification → Redis    │
└──────┬────────────────────┬──────────┘
       │                    │
       ▼                    ▼
┌─────────────┐      ┌─────────────┐
│  Cassandra  │      │   Redis     │
│  messages   │      │  Pub/Sub    │
│  table      │      │  Channel:   │
│             │      │  notif:     │
│             │      │  {userId}   │
└─────────────┘      └──────┬──────┘
                            │
                            ▼
                     ┌──────────────┐
                     │  WebSocket   │
                     │  Gateway     │
                     └──────┬───────┘
                            │ ws://
                            ▼
                      [ Cliente ]
                      Notificação
                      em tempo real
```

### Componentes

| Componente | Responsabilidade | Porta | Tecnologia |
|------------|------------------|-------|------------|
| **API Service** | Endpoints gRPC, validação JWT | 9091 | Java 17, gRPC |
| **Kafka** | Message broker, event log | 9092 | Apache Kafka |
| **Router Worker** | Processar mensagens, persist, notify | - | Java 17, Kafka Consumer |
| **Cassandra** | Banco de dados distribuído | 9042 | Cassandra 4.1 |
| **Redis** | Pub/Sub para notificações | 6379 | Redis 7 |
| **WebSocket Gateway** | Real-time push notifications | 8765 | Netty, Java |
| **Prometheus** | Métricas e monitoramento | 9090 | Prometheus |
| **Grafana** | Dashboards e visualização | 3000 | Grafana |

---

## 📁 Estrutura do Projeto

```
chat4alltijolim-001-basic-messaging-api/
├── api-service/              # Serviço gRPC principal
│   ├── src/main/proto/       # Definições Protocol Buffers
│   │   ├── auth.proto        # Autenticação (Register, Login)
│   │   ├── messages.proto    # Mensagens (SendMessage, GetMessages)
│   │   ├── groups.proto      # Grupos
│   │   ├── files.proto       # Upload/Download de arquivos
│   │   └── health.proto      # Health check
│   ├── src/main/java/chat4all/api/
│   │   ├── grpc/service/     # Implementações gRPC
│   │   │   ├── MessageServiceImpl.java
│   │   │   ├── AuthServiceImpl.java
│   │   │   └── FileServiceImpl.java
│   │   ├── grpc/interceptor/ # Interceptors (JWT)
│   │   │   └── AuthInterceptor.java
│   │   ├── kafka/            # Produtor Kafka
│   │   │   └── MessageProducer.java
│   │   ├── cassandra/        # Repositório Cassandra
│   │   │   └── CassandraMessageRepository.java
│   │   └── auth/             # Geração de JWT
│   │       └── TokenGenerator.java
│   └── pom.xml
│
├── router-worker/            # Worker processador de mensagens
│   ├── src/main/java/chat4all/worker/
│   │   ├── kafka/            # Consumidor Kafka
│   │   │   └── MessageConsumer.java
│   │   ├── processing/       # Lógica de negócio
│   │   │   └── MessageProcessor.java
│   │   ├── cassandra/        # Persistência
│   │   │   └── CassandraMessageStore.java
│   │   └── notifications/    # Redis Pub/Sub
│   │       └── RedisNotificationPublisher.java
│   └── pom.xml
│
├── websocket-gateway/        # Gateway WebSocket
│   ├── src/main/java/chat4all/websocket/
│   │   └── NotificationWebSocketServer.java
│   └── pom.xml
│
├── shared/                   # Código compartilhado
│   └── src/main/java/chat4all/shared/
│
├── cassandra-init/           # Scripts de inicialização
│   ├── schema.cql            # Schema Cassandra
│   └── init.sh
│
├── monitoring/               # Configurações de monitoramento
│   ├── prometheus.yml
│   ├── prometheus-alerts.yml
│   └── grafana/dashboards/
│
├── connector-whatsapp/       # Conector WhatsApp (futuro)
├── connector-instagram/      # Conector Instagram (futuro)
│
├── docker-compose.yml        # Orquestração completa
├── pom.xml                   # Maven parent POM
├── openapi.yaml              # Documentação da API
├── test_e2e_working.sh       # ✅ Script de teste E2E (TESTADO)
├── TESTING_RULES.md          # 📋 Regras obrigatórias de teste
└── README.md                 # Este arquivo
```

---

## 🧪 Testes e Validação

### 1. Teste End-to-End Automático

```bash
./test_e2e_working.sh
```

**Status:** ✅ Testado e funcionando  
**Exit Code:** 0 (sucesso)  
**Última execução:** 2025-12-07

### 2. Verificar Logs de Auditoria

```bash
# Logs do Worker processando mensagens
docker-compose logs -f router-worker

# Logs da API recebendo requisições
docker-compose logs -f api-service

# Logs do Kafka
docker-compose logs -f kafka
```

### 3. Monitoramento

**Prometheus:** http://localhost:9090

Métricas disponíveis:
- `chat4all_messages_sent_total` - Total de mensagens enviadas
- `chat4all_messages_processed_total` - Total processadas pelo worker
- `chat4all_kafka_publish_duration_seconds` - Latência de publicação
- `chat4all_cassandra_write_duration_seconds` - Latência de escrita

**Grafana:** http://localhost:3000

Dashboards:
- API Service Overview
- Router Worker Performance
- System Overview

Credenciais padrão:
- User: `admin`
- Password: `admin`

### 4. Verificar Persistência no Cassandra

```bash
# Entrar no container
docker exec -it chat4all-cassandra cqlsh

# Query messages
USE chat4all;
SELECT * FROM messages LIMIT 10;
SELECT * FROM messages WHERE conversation_id = 'direct_user_A_user_B';
```

---

## 🔍 Exemplos de Uso

### Cenário 1: Conversa 1:1

```bash
# 1. Criar Alice
grpcurl -plaintext -d '{"username":"alice","email":"alice@test.com","password":"pass123"}' \
  localhost:9091 chat4all.AuthService/Register

# 2. Criar Bob
grpcurl -plaintext -d '{"username":"bob","email":"bob@test.com","password":"pass123"}' \
  localhost:9091 chat4all.AuthService/Register

# 3. Login Bob
BOB_TOKEN=$(grpcurl -plaintext -d '{"username":"bob","password":"pass123"}' \
  localhost:9091 chat4all.AuthService/Login | jq -r '.accessToken')

# 4. Bob envia mensagem para Alice
grpcurl -plaintext \
  -H "authorization: Bearer $BOB_TOKEN" \
  -d '{
    "conversation_id": "direct_user_bob_user_alice",
    "content": "Oi Alice, tudo bem?",
    "recipient_id": "user_alice"
  }' localhost:9091 chat4all.MessageService/SendMessage

# 5. Login Alice
ALICE_TOKEN=$(grpcurl -plaintext -d '{"username":"alice","password":"pass123"}' \
  localhost:9091 chat4all.AuthService/Login | jq -r '.accessToken')

# 6. Alice recupera mensagens
grpcurl -plaintext \
  -H "authorization: Bearer $ALICE_TOKEN" \
  -d '{
    "conversation_id": "direct_user_bob_user_alice",
    "limit": 50
  }' localhost:9091 chat4all.MessageService/GetMessages
```

---

## 📚 Documentação Adicional

- **OpenAPI Spec:** `openapi.yaml` - Documentação completa da API
- **Protocol Buffers:** `api-service/src/main/proto/` - Definições de tipos
- **Schema Cassandra:** `cassandra-init/schema.cql` - Estrutura do banco

---

## 🛠️ Troubleshooting

### Serviços não iniciam

```bash
# Ver logs de todos os serviços
docker-compose logs

# Rebuild completo
docker-compose down -v
docker-compose up --build -d
```

### Kafka não está acessível

```bash
# Verificar se Kafka está healthy
docker-compose ps kafka

# Verificar logs
docker-compose logs kafka
docker-compose logs zookeeper
```

### Cassandra não aceita conexões

```bash
# Aguardar inicialização (pode levar 60s)
docker-compose logs cassandra | grep "Startup complete"

# Verificar conectividade
docker exec -it chat4all-cassandra cqlsh
```

### Mensagens não chegam

```bash
# 1. Verificar Kafka
docker-compose logs kafka | grep "messages"

# 2. Verificar Worker
docker-compose logs router-worker | grep "Processing"

# 3. Verificar Cassandra
docker exec -it chat4all-cassandra cqlsh -e "SELECT COUNT(*) FROM chat4all.messages;"
```

---

## 📝 Notas de Desenvolvimento

- **JWT Secret:** Chave estática `chat4all-secret-key` para demonstração. Em produção, usar variável de ambiente.
- **Particionamento Kafka:** Mensagens da mesma conversa sempre na mesma partition (ordem garantida).
- **Cassandra Partition Key:** `conversation_id` para distribuir carga uniformemente.
- **Status Lifecycle:** `SENT` → `DELIVERED` → `READ` (apenas SENT/DELIVERED implementados).

---

## ✅ Checklist de Entregas

- [x] API funcional (POST /v1/messages via gRPC SendMessage)
- [x] API funcional (GET /v1/conversations/{id}/messages via gRPC GetMessages)
- [x] Autenticação JWT implementada e funcionando
- [x] Kafka configurado e em execução
- [x] Produtor Kafka na API publicando mensagens
- [x] Consumidor (worker) processando mensagens do Kafka
- [x] Banco de dados Cassandra armazenando mensagens
- [x] Worker atualizando status (SENT → DELIVERED)
- [x] Logs de auditoria detalhados
- [x] Documentação completa dos endpoints (README + openapi.yaml)
- [x] Script de inicialização automática (docker-compose.yml)
- [x] Log de execução demonstrando troca entre 2 usuários (test_e2e_working.sh - TESTADO ✅)
- [x] Arquitetura documentada com diagramas
- [x] Exemplos de uso com grpcurl
- [x] Regras de teste documentadas (TESTING_RULES.md)

---

## 📄 Licença

MIT License - Projeto Educacional

---

**Desenvolvido para demonstração de conceitos de Sistemas Distribuídos**
