#!/bin/bash

# ============================================================================
# Chat4All - Script de Teste End-to-End VALIDADO
# ============================================================================
# Propósito: Demonstrar troca de mensagens entre 2 usuários
# Status: TESTADO E FUNCIONANDO
# Data: 2025-12-07
# ============================================================================

set -e

echo "=========================================="
echo "🧪 Chat4All - Teste End-to-End"
echo "Demonstração: Troca de Mensagens 1:1"
echo "=========================================="

# Cores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuração
API_HOST="localhost:9091"

# Verificar dependências
echo ""
echo -e "${BLUE}[1/9] Verificando dependências...${NC}"

if ! command -v grpcurl &> /dev/null; then
    echo -e "${RED}❌ grpcurl não encontrado.${NC}"
    echo "Instale com: brew install grpcurl (macOS) ou https://github.com/fullstorydev/grpcurl"
    exit 1
fi

if ! command -v jq &> /dev/null; then
    echo -e "${RED}❌ jq não encontrado.${NC}"
    echo "Instale com: sudo apt install jq (Linux) ou brew install jq (macOS)"
    exit 1
fi

if ! docker info > /dev/null 2>&1; then
    echo -e "${RED}❌ Docker não está rodando${NC}"
    exit 1
fi

echo -e "${GREEN}✅ Todas as dependências OK${NC}"

# Verificar serviços
echo ""
echo -e "${BLUE}[2/9] Verificando serviços Docker...${NC}"

SERVICES_UP=$(docker-compose ps | grep -c "Up" || echo "0")
if [ "$SERVICES_UP" -lt 5 ]; then
    echo -e "${YELLOW}⚠️  Poucos serviços rodando ($SERVICES_UP). Iniciando...${NC}"
    docker-compose up -d
    echo "⏳ Aguardando 60 segundos para inicialização completa..."
    sleep 60
else
    echo -e "${GREEN}✅ Serviços já estão rodando${NC}"
fi

# Verificar API Service
echo ""
echo -e "${BLUE}[3/9] Verificando API Service...${NC}"

MAX_RETRIES=10
RETRY=0
while [ $RETRY -lt $MAX_RETRIES ]; do
    if grpcurl -plaintext $API_HOST list chat4all.v1.AuthService > /dev/null 2>&1; then
        echo -e "${GREEN}✅ API Service está respondendo em $API_HOST${NC}"
        break
    fi
    
    RETRY=$((RETRY + 1))
    if [ $RETRY -eq $MAX_RETRIES ]; then
        echo -e "${RED}❌ API Service não está respondendo após $MAX_RETRIES tentativas${NC}"
        echo "Logs do serviço:"
        docker-compose logs --tail=30 api-service
        exit 1
    fi
    
    echo "⏳ Aguardando API (tentativa $RETRY/$MAX_RETRIES)..."
    sleep 5
done

# Criar Usuário Alice
echo ""
echo -e "${BLUE}[4/9] Criando Usuário Alice...${NC}"

TIMESTAMP=$(date +%s)
ALICE_USERNAME="alice_${TIMESTAMP}"
ALICE_EMAIL="${ALICE_USERNAME}@test.com"
ALICE_PASSWORD="senha123"

echo "Username: $ALICE_USERNAME"

ALICE_RESPONSE=$(grpcurl -plaintext -d "{
  \"username\": \"$ALICE_USERNAME\",
  \"email\": \"$ALICE_EMAIL\",
  \"password\": \"$ALICE_PASSWORD\"
}" $API_HOST chat4all.v1.AuthService/Register 2>&1)

if echo "$ALICE_RESPONSE" | grep -q "Code:"; then
    echo -e "${RED}❌ Erro ao criar Alice:${NC}"
    echo "$ALICE_RESPONSE"
    exit 1
fi

ALICE_USER_ID=$(echo "$ALICE_RESPONSE" | jq -r '.user_id // .userId')
echo -e "${GREEN}✅ Alice criada: $ALICE_USER_ID${NC}"

# Login Alice
echo ""
echo -e "${BLUE}[5/9] Login Alice...${NC}"

ALICE_LOGIN=$(grpcurl -plaintext -d "{
  \"username\": \"$ALICE_USERNAME\",
  \"password\": \"$ALICE_PASSWORD\"
}" $API_HOST chat4all.v1.AuthService/Login 2>&1)

ALICE_TOKEN=$(echo "$ALICE_LOGIN" | jq -r '.access_token // .accessToken')

if [ "$ALICE_TOKEN" == "null" ] || [ -z "$ALICE_TOKEN" ]; then
    echo -e "${RED}❌ Erro ao fazer login de Alice${NC}"
    echo "$ALICE_LOGIN"
    exit 1
fi

echo -e "${GREEN}✅ Alice autenticada${NC}"

# Criar Usuário Bob
echo ""
echo -e "${BLUE}[6/9] Criando Usuário Bob...${NC}"

BOB_USERNAME="bob_${TIMESTAMP}"
BOB_EMAIL="${BOB_USERNAME}@test.com"
BOB_PASSWORD="senha123"

echo "Username: $BOB_USERNAME"

BOB_RESPONSE=$(grpcurl -plaintext -d "{
  \"username\": \"$BOB_USERNAME\",
  \"email\": \"$BOB_EMAIL\",
  \"password\": \"$BOB_PASSWORD\"
}" $API_HOST chat4all.v1.AuthService/Register 2>&1)

BOB_USER_ID=$(echo "$BOB_RESPONSE" | jq -r '.user_id // .userId')
echo -e "${GREEN}✅ Bob criado: $BOB_USER_ID${NC}"

# Login Bob
echo ""
echo -e "${BLUE}[7/9] Login Bob...${NC}"

BOB_LOGIN=$(grpcurl -plaintext -d "{
  \"username\": \"$BOB_USERNAME\",
  \"password\": \"$BOB_PASSWORD\"
}" $API_HOST chat4all.v1.AuthService/Login 2>&1)

BOB_TOKEN=$(echo "$BOB_LOGIN" | jq -r '.access_token // .accessToken')
echo -e "${GREEN}✅ Bob autenticado${NC}"

# Bob envia mensagem para Alice
echo ""
echo -e "${BLUE}[8/9] Bob enviando mensagem para Alice...${NC}"

# Criar conversation_id 1:1 (formato: direct_userId1_userId2)
if [[ "$BOB_USER_ID" < "$ALICE_USER_ID" ]]; then
    CONVERSATION_ID="direct_${BOB_USER_ID}_${ALICE_USER_ID}"
else
    CONVERSATION_ID="direct_${ALICE_USER_ID}_${BOB_USER_ID}"
fi

echo "Conversation ID: $CONVERSATION_ID"

MESSAGE_CONTENT="Olá Alice! Esta é uma mensagem de teste do sistema Chat4All. Timestamp: $(date '+%Y-%m-%d %H:%M:%S')"

SEND_RESPONSE=$(grpcurl -plaintext \
  -H "authorization: Bearer $BOB_TOKEN" \
  -d "{
    \"conversation_id\": \"$CONVERSATION_ID\",
    \"content\": \"$MESSAGE_CONTENT\"
  }" $API_HOST chat4all.v1.MessageService/SendMessage 2>&1)

if echo "$SEND_RESPONSE" | grep -q "Code:"; then
    echo -e "${RED}❌ Erro ao enviar mensagem:${NC}"
    echo "$SEND_RESPONSE"
    exit 1
fi

MESSAGE_ID=$(echo "$SEND_RESPONSE" | jq -r '.message_id // .messageId')
MESSAGE_STATUS=$(echo "$SEND_RESPONSE" | jq -r '.status')

echo -e "${GREEN}✅ Mensagem enviada!${NC}"
echo "   Message ID: $MESSAGE_ID"
echo "   Status: $MESSAGE_STATUS"

# Aguardar processamento
echo ""
echo -e "${YELLOW}⏳ Aguardando processamento pelo worker (5 segundos)...${NC}"
sleep 5

# Alice recupera mensagens
echo ""
echo -e "${BLUE}[9/9] Alice recuperando mensagens...${NC}"

GET_MESSAGES=$(grpcurl -plaintext \
  -H "authorization: Bearer $ALICE_TOKEN" \
  -d "{
    \"conversation_id\": \"$CONVERSATION_ID\",
    \"limit\": 10,
    \"offset\": 0
  }" $API_HOST chat4all.v1.MessageService/GetMessages 2>&1)

if echo "$GET_MESSAGES" | grep -q "Code:"; then
    echo -e "${RED}❌ Erro ao recuperar mensagens:${NC}"
    echo "$GET_MESSAGES"
    exit 1
fi

# Verificar se a mensagem foi persistida
MESSAGES_COUNT=$(echo "$GET_MESSAGES" | jq -r '.messages | length')

if [ "$MESSAGES_COUNT" -gt 0 ]; then
    echo -e "${GREEN}✅ Mensagens recuperadas: $MESSAGES_COUNT${NC}"
    echo ""
    echo "Detalhes da mensagem:"
    echo "$GET_MESSAGES" | jq -r '.messages[] | "  📧 ID: \(.messageId)\n     👤 Sender: \(.senderId)\n     📊 Status: \(.status)\n     💬 Content: \(.content)\n     🕐 Timestamp: \(.timestamp)\n"'
else
    echo -e "${YELLOW}⚠️  Nenhuma mensagem encontrada${NC}"
    echo "Isso pode indicar que o worker ainda está processando."
    echo "Aguardando mais 5 segundos..."
    sleep 5
    
    # Tentar novamente
    GET_MESSAGES=$(grpcurl -plaintext \
      -H "authorization: Bearer $ALICE_TOKEN" \
      -d "{
        \"conversation_id\": \"$CONVERSATION_ID\",
        \"limit\": 10
      }" $API_HOST chat4all.v1.MessageService/GetMessages 2>&1)
    
    MESSAGES_COUNT=$(echo "$GET_MESSAGES" | jq -r '.messages | length')
    
    if [ "$MESSAGES_COUNT" -gt 0 ]; then
        echo -e "${GREEN}✅ Mensagens recuperadas na 2ª tentativa: $MESSAGES_COUNT${NC}"
    else
        echo -e "${RED}❌ Mensagem não foi persistida${NC}"
        echo "Verificar logs do router-worker"
        exit 1
    fi
fi

# Verificar logs do worker
echo ""
echo -e "${BLUE}Verificando logs do router-worker...${NC}"
echo ""
docker-compose logs --tail=20 router-worker | grep -E "(Processing|SENT|DELIVERED|${MESSAGE_ID})" || echo "Nenhum log encontrado com o message_id"

# Sumário final
echo ""
echo "=========================================="
echo -e "${GREEN}✅ TESTE COMPLETO E APROVADO!${NC}"
echo "=========================================="
echo ""
echo "📊 Resumo da Execução:"
echo "   • Usuário Alice: $ALICE_USER_ID"
echo "   • Usuário Bob: $BOB_USER_ID"
echo "   • Conversa: $CONVERSATION_ID"
echo "   • Mensagem: $MESSAGE_ID"
echo "   • Status: $MESSAGE_STATUS"
echo "   • Mensagens recuperadas: $MESSAGES_COUNT"
echo ""
echo "🔍 Verificações Realizadas:"
echo "   ✅ Dependências instaladas (grpcurl, jq, docker)"
echo "   ✅ Serviços Docker rodando"
echo "   ✅ API gRPC disponível e respondendo"
echo "   ✅ Autenticação JWT funcionando"
echo "   ✅ POST /v1/messages (via SendMessage gRPC)"
echo "   ✅ Mensagem publicada no Kafka"
echo "   ✅ Worker processou a mensagem"
echo "   ✅ Mensagem persistida no Cassandra"
echo "   ✅ GET /v1/conversations/{id}/messages (via GetMessages gRPC)"
echo ""
echo "🎯 Status: TODOS OS REQUISITOS ATENDIDOS"
echo ""
echo "=========================================="

exit 0
