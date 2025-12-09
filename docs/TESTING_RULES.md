# 🧪 REGRAS DE TESTE - OBRIGATÓRIAS

## ⚠️ ATENÇÃO: SEMPRE TESTAR ANTES DE ENTREGAR

**REGRA FUNDAMENTAL:** Toda funcionalidade implementada DEVE ser testada antes de ser entregue ao usuário.

---

## 📋 Checklist Obrigatório

Antes de considerar qualquer tarefa completa, SEMPRE:

### ✅ 1. Testar Localmente
```bash
# Iniciar os serviços
docker-compose up -d

# Aguardar inicialização (45-60 segundos)
sleep 45

# Verificar que serviços estão rodando
docker-compose ps | grep -E "(healthy|Up)"
```

### ✅ 2. Testar API Básica
```bash
# Verificar que gRPC está respondendo
grpcurl -plaintext localhost:9091 list

# Deve retornar:
# chat4all.v1.AuthService
# chat4all.v1.MessageService
# chat4all.v1.HealthService
```

### ✅ 3. Executar Script de Teste
```bash
# Sempre executar o script de teste end-to-end
./test_e2e_working.sh

# Verificar que retorna exit code 0
echo $?  # Deve mostrar 0
```

### ✅ 4. Verificar Logs
```bash
# Ver logs de erros
docker-compose logs | grep -i error

# Ver logs do worker processando
docker-compose logs router-worker | grep "Processing message"
```

---

## 🚫 NÃO ENTREGAR SE:

- [ ] Serviços não iniciaram corretamente
- [ ] API não responde a grpcurl
- [ ] Script de teste falha
- [ ] Há erros nos logs
- [ ] Funcionalidade não foi testada manualmente

---

## 📝 Documentação de Testes

### Script de Teste Principal

**Arquivo:** `test_e2e_working.sh`

**O que testa:**
1. Dependências instaladas (grpcurl, jq, docker)
2. Serviços rodando (Kafka, Cassandra, Redis, API)
3. Criação de usuários
4. Autenticação JWT
5. Envio de mensagens
6. Persistência no Cassandra
7. Recuperação de mensagens
8. Logs de auditoria do worker

**Como usar:**
```bash
# Executar teste completo
./test_e2e_working.sh

# Ver detalhes se falhar
./test_e2e_working.sh 2>&1 | tee test_output.log
```

---

## 🔄 Processo de Desenvolvimento

### SEMPRE seguir este fluxo:

1. **Implementar** → Escrever código
2. **Testar Unitário** → Verificar função isolada
3. **Testar Integração** → Verificar com dependências
4. **Testar E2E** → Executar script completo
5. **Verificar Logs** → Garantir sem erros
6. **Documentar** → Atualizar README se necessário
7. **Entregar** → Só depois de TUDO validado

---

## ⚡ Comandos Rápidos de Teste

```bash
# Teste completo (1 comando)
docker-compose up -d && sleep 45 && ./test_e2e_working.sh

# Reiniciar e testar
docker-compose down && docker-compose up -d && sleep 45 && ./test_e2e_working.sh

# Verificar saúde dos serviços
docker-compose ps | grep healthy

# Ver últimos erros
docker-compose logs --tail=50 | grep -i error
```

---

## 📊 Critérios de Sucesso

Um teste é considerado **APROVADO** quando:

- ✅ Exit code = 0
- ✅ Todos os serviços "healthy"
- ✅ API responde a requisições
- ✅ Mensagens são persistidas
- ✅ Logs mostram processamento correto
- ✅ Sem erros ou exceptions nos logs

---

## 🐛 Troubleshooting

### Se o teste falhar:

1. **Verificar serviços:**
   ```bash
   docker-compose ps
   docker-compose logs api-service
   docker-compose logs router-worker
   ```

2. **Reiniciar do zero:**
   ```bash
   docker-compose down -v
   docker-compose up -d
   sleep 60
   ```

3. **Verificar dependências:**
   ```bash
   grpcurl --version
   jq --version
   docker --version
   ```

4. **Limpar ambiente:**
   ```bash
   docker system prune -f
   docker volume prune -f
   ```

---

## 📌 LEMBRETE FINAL

**NUNCA, EM HIPÓTESE ALGUMA, ENTREGAR CÓDIGO NÃO TESTADO**

Se você está lendo isto, lembre-se:
- ✅ Testar é OBRIGATÓRIO, não opcional
- ✅ O teste deve passar ANTES da entrega
- ✅ Logs devem estar limpos de erros
- ✅ Documentação deve refletir a realidade

---

**Data de criação:** 2025-12-07  
**Última atualização:** 2025-12-07  
**Status:** OBRIGATÓRIO PARA SEMPRE
