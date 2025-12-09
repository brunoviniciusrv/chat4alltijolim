#!/bin/bash

echo "=========================================="
echo "TESTE COMPLETO DE GRUPOS - Chat4All"
echo "=========================================="
echo ""

API_URL="http://localhost:8081"

# Cores
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Função para criar usuário
create_user() {
    local username=$1
    local password=$2
    echo -e "${YELLOW}Criando usuário: ${username}${NC}"
    
    response=$(curl -s -X POST "${API_URL}/register" \
        -H "Content-Type: application/json" \
        -d "{\"username\": \"${username}\", \"password\": \"${password}\"}")
    
    echo "$response"
}

# Função para fazer login
login_user() {
    local username=$1
    local password=$2
    echo -e "${YELLOW}Login do usuário: ${username}${NC}"
    
    response=$(curl -s -X POST "${API_URL}/login" \
        -H "Content-Type: application/json" \
        -d "{\"username\": \"${username}\", \"password\": \"${password}\"}")
    
    echo "$response" | jq -r '.token'
}

# Função para listar grupos
list_groups() {
    local user_id=$1
    local token=$2
    echo -e "${YELLOW}Listando grupos do usuário: ${user_id}${NC}"
    
    response=$(curl -s -X GET "${API_URL}/groups?userId=${user_id}" \
        -H "Authorization: Bearer ${token}")
    
    echo "$response"
}

# Função para criar grupo
create_group() {
    local token=$1
    local group_name=$2
    shift 2
    local member_ids=("$@")
    
    echo -e "${YELLOW}Criando grupo: ${group_name}${NC}"
    
    # Construir array JSON de member_ids
    members_json=$(printf ',"%s"' "${member_ids[@]}")
    members_json="[${members_json:1}]"
    
    response=$(curl -s -X POST "${API_URL}/groups" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer ${token}" \
        -d "{\"groupName\": \"${group_name}\", \"memberIds\": ${members_json}}")
    
    echo "$response"
}

# Função para enviar mensagem
send_message() {
    local conversation_id=$1
    local sender_id=$2
    local content=$3
    
    echo -e "${YELLOW}Enviando mensagem para: ${conversation_id}${NC}"
    
    response=$(curl -s -X POST "${API_URL}/messages" \
        -H "Content-Type: application/json" \
        -d "{\"conversationId\": \"${conversation_id}\", \"senderId\": \"${sender_id}\", \"content\": \"${content}\"}")
    
    echo "$response"
}

# Passo 1: Criar usuários
echo ""
echo "=========================================="
echo "PASSO 1: Criar usuários de teste"
echo "=========================================="

user1=$(create_user "testuser1" "password123")
user1_id=$(echo "$user1" | jq -r '.userId // .user_id')
echo -e "${GREEN}✓ User1 criado: ${user1_id}${NC}"

user2=$(create_user "testuser2" "password123")
user2_id=$(echo "$user2" | jq -r '.userId // .user_id')
echo -e "${GREEN}✓ User2 criado: ${user2_id}${NC}"

user3=$(create_user "testuser3" "password123")
user3_id=$(echo "$user3" | jq -r '.userId // .user_id')
echo -e "${GREEN}✓ User3 criado: ${user3_id}${NC}"

# Passo 2: Fazer login
echo ""
echo "=========================================="
echo "PASSO 2: Fazer login"
echo "=========================================="

token1=$(login_user "testuser1" "password123")
echo -e "${GREEN}✓ Token User1: ${token1:0:20}...${NC}"

token2=$(login_user "testuser2" "password123")
echo -e "${GREEN}✓ Token User2: ${token2:0:20}...${NC}"

token3=$(login_user "testuser3" "password123")
echo -e "${GREEN}✓ Token User3: ${token3:0:20}...${NC}"

# Passo 3: Criar grupo
echo ""
echo "=========================================="
echo "PASSO 3: Criar grupo"
echo "=========================================="

group=$(create_group "$token1" "Grupo de Teste" "$user1_id" "$user2_id" "$user3_id")
echo "Resposta: $group"
group_id=$(echo "$group" | jq -r '.group_id // .groupId')
echo -e "${GREEN}✓ Grupo criado: ${group_id}${NC}"

# Passo 4: Listar grupos de cada usuário
echo ""
echo "=========================================="
echo "PASSO 4: Listar grupos"
echo "=========================================="

echo "Grupos do User1:"
groups1=$(list_groups "$user1_id" "$token1")
echo "$groups1" | jq '.'

echo ""
echo "Grupos do User2:"
groups2=$(list_groups "$user2_id" "$token2")
echo "$groups2" | jq '.'

echo ""
echo "Grupos do User3:"
groups3=$(list_groups "$user3_id" "$token3")
echo "$groups3" | jq '.'

# Passo 5: Enviar mensagem no grupo
echo ""
echo "=========================================="
echo "PASSO 5: Enviar mensagem no grupo"
echo "=========================================="

msg_result=$(send_message "$group_id" "$user1_id" "Olá pessoal! Mensagem de teste no grupo")
echo "Resposta: $msg_result"
msg_id=$(echo "$msg_result" | jq -r '.messageId // .message_id')
echo -e "${GREEN}✓ Mensagem enviada: ${msg_id}${NC}"

# Passo 6: Verificar logs
echo ""
echo "=========================================="
echo "PASSO 6: Verificar logs do sistema"
echo "=========================================="

echo "Logs do API Service (últimas 20 linhas):"
docker-compose logs --tail=20 api-service | grep -E "CreateGroup|ListGroups|SendMessage|Kafka"

echo ""
echo "Logs do Router Worker (últimas 20 linhas):"
docker-compose logs --tail=20 router-worker | grep -E "Processing|group_"

echo ""
echo "=========================================="
echo "TESTE COMPLETO!"
echo "=========================================="
