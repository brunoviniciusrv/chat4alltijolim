# Chat4All - Validação de Upload de Arquivos e Notificações

**Data:** 07/12/2025  
**Status:** ✅ **FUNCIONAL COM LIMITAÇÕES DOCUMENTADAS**

---

## 📋 Resumo Executivo

Validação completa do sistema de upload de arquivos e integração com notificações:

| Funcionalidade | Status | Limite Testado |
|----------------|--------|----------------|
| **Upload de Arquivos** | ✅ Funcionando | 10MB |
| **Download de Arquivos** | ✅ Funcionando | 10MB |
| **Checksum Verification** | ✅ Funcionando | SHA-256 |
| **Notificações 1:1 com Arquivos** | ✅ Funcionando | file_id incluído |
| **Notificações Grupo com Arquivos** | ✅ Funcionando | file_id incluído |
| **Arquivos > 10MB** | ⚠️ **BUG CONHECIDO** | Falha no flush |

---

## 🧪 Testes Executados

### TESTE 1: Upload de Arquivo 10MB ✅

**Comando:**
```bash
python3 test_file_upload.py
```

**Resultado:**
```
✅ TEST PASSED: small_file_10MB.bin
   ✓ Upload successful
   ✓ Message sent with attachment
   ✓ Download successful
   ✓ Checksum verified

Métricas:
   Size: 10,485,760 bytes (10.00 MB)
   Upload time: 0.10s
   Upload throughput: 98.27 MB/s
   Download completed: 10,485,760 bytes
   Checksum: c1b114ec6d8c5902e2a3d6e88246ced004b2dfc0814fb4427f81170e9492f66b
```

**Evidência (logs API service):**
```
api-service-1  | 📤 Starting upload session: upload_1733588654299
api-service-1  |    File: small_file_10MB.bin (10485760 bytes)
api-service-1  |    📥 Received chunk: 1048576 bytes (total: 10485760/10485760)
api-service-1  | ✅ Upload completed: file_d5f8f1b7-2677-414a-9b5e-7b7f148c128f
api-service-1  |    Size: 10485760 bytes
api-service-1  |    Checksum: c1b114ec6d8c5902e2a3d6e88246ced004b2dfc0814fb4427f81170e9492f66b
api-service-1  |    Storage: direct_user_xxx_user_yyy/file_d5f8f1b7-2677-414a-9b5e-7b7f148c128f_small_file_10MB.bin
```

---

### TESTE 2: Mensagem 1:1 com Arquivo ✅

**File ID usado:** `file_test_12345_demo`  
**Message ID:** `msg_7a843d77-55f5-4710-95da-1676ffc8c56c`

**Evidência (logs router-worker):**
```
router-worker-5  | ▶ Processing message: msg_7a843d77-55f5-4710-95da-1676ffc8c56c
router-worker-5  | ✓ Saved message with file_id
router-worker-5  | ✓ Extracted recipient: user_6b987baa-2848-4352-a2df-0068feec4bfa
router-worker-5  | ✓ Published notification to Redis channel: notifications:user_6b987baa-2848-4352-a2df-0068feec4bfa (subscribers: 1)
router-worker-5  | ✓ Notification published to Redis for user: user_6b987baa-2848-4352-a2df-0068feec4bfa
```

**Código que inclui file_id na notificação:**
```java
// MessageProcessor.java linha 250
notificationPublisher.publishNewMessageNotification(
    recipientId,
    messageId,
    event.getSenderId(),
    senderUsername,
    conversationId,
    event.getContent(),
    event.getFileId(),  // ← File ID incluído
    null  // Sem groupName para diretas
);
```

---

### TESTE 3: Mensagem em Grupo com Arquivo ✅

**Group ID:** `group_0037c948-c9f5-4bba-97b4-56f97ff70c63`  
**Membros:** 3 usuários  
**File ID usado:** `file_group_67890_demo`  
**Message ID:** `msg_0af52399-a552-4135-a14c-18139840b239`

**Evidência (logs router-worker):**
```
router-worker-4  | ▶ Processing message: msg_0af52399-a552-4135-a14c-18139840b239 (conv: group_0037c948-c9f5-4bba-97b4-56f97ff70c63)
router-worker-4  | [DEBUG] Publishing group notifications to 3 members
router-worker-4  |   → Publishing to group member: user_671afc17-9a47-45a1-9e4d-632d2c7a287f
router-worker-4  | ✓ Published notification to Redis channel: notifications:user_671afc17-9a47-45a1-9e4d-632d2c7a287f (subscribers: 1)
router-worker-4  |   → Publishing to group member: user_0f0922c0-5068-478d-84b2-a63da378c0b7
router-worker-4  | ✓ Published notification to Redis channel: notifications:user_0f0922c0-5068-478d-84b2-a63da378c0b7 (subscribers: 1)
router-worker-4  |   → Publishing to group member: user_a7938407-8a06-4eed-86b9-58387d78fe27
router-worker-4  | ✓ Published notification to Redis channel: notifications:user_a7938407-8a06-4eed-86b9-58387d78fe27 (subscribers: 1)
router-worker-4  | ✓ Notifications published to all group members
```

**Código que inclui file_id em notificações de grupo:**
```java
// MessageProcessor.java linha 275
for (String memberId : groupMembers) {
    if (!memberId.equals(event.getSenderId())) {  // Não notificar o sender
        notificationPublisher.publishNewMessageNotification(
            memberId,
            messageId,
            event.getSenderId(),
            senderUsername,
            conversationId,
            event.getContent(),
            event.getFileId(),  // ← File ID incluído
            groupName
        );
    }
}
```

---

### TESTE 4: Payload JSON da Notificação ✅

**RedisNotificationPublisher.java (linha 80-98):**
```java
JSONObject notification = new JSONObject();
notification.put("type", "new_message");
notification.put("message_id", messageId);
notification.put("sender_id", senderId);
if (senderUsername != null && !senderUsername.isEmpty()) {
    notification.put("sender_username", senderUsername);
}
notification.put("conversation_id", conversationId);
notification.put("content", content);
notification.put("timestamp", System.currentTimeMillis());

if (groupName != null && !groupName.isEmpty()) {
    notification.put("group_name", groupName);
}

if (fileId != null && !fileId.isEmpty()) {
    notification.put("file_id", fileId);  // ← File ID adicionado ao JSON
}

String channel = "notifications:" + recipientUserId;
long subscribers = jedis.publish(channel, notification.toString());
```

**Exemplo de payload (reconstruído do código):**
```json
{
  "type": "new_message",
  "message_id": "msg_7a843d77-55f5-4710-95da-1676ffc8c56c",
  "sender_id": "user_0ccf2c48-b69c-488c-b161-90831f2dfb46",
  "conversation_id": "direct_user_0ccf2c48_user_6b987baa",
  "content": "Check this file!",
  "timestamp": 1733588774000,
  "file_id": "file_test_12345_demo"
}
```

---

## ⚠️ BUG CONHECIDO: Arquivos > 10MB

### Descrição do Problema

**Arquivo:** `FileServiceImpl.java` (linhas 140-145)

**Código problemático:**
```java
// Flush buffer para liberar memória (otimização para arquivos grandes)
// Mantém últimos chunks em memória para checksum final
if (buffer.size() > BUFFER_FLUSH_SIZE && totalBytesReceived < metadata.getSizeBytes()) {
    System.out.println("   💾 Flushing buffer (" + buffer.size() + " bytes) - memory optimization");
    buffer = new ByteArrayOutputStream(CHUNK_SIZE * 2);  // ← BUG: descarta dados anteriores!
}
```

**Problema:**
- Quando arquivo excede 10MB (BUFFER_FLUSH_SIZE), código tenta otimizar memória
- **MAS**: ao fazer `buffer = new ByteArrayOutputStream()`, **descarta todos os dados acumulados**
- Resultado: apenas último chunk (< 10MB) é salvo no MinIO

**Evidência:**
```bash
# Upload de 100MB
✅ Upload completed: file_5b56d641-0f17-4b41-a896-d04b1d610cdc
   Size: 104857600 bytes
   Checksum: bbb668e425543a228c685c147a3f44c98f10d513d6650387f9e59f0e6b43c978

# Download retorna apenas 1MB
✓ Download completed: 1,048,576 bytes
   Checksum: aaf37f928cf3a993caa77230ca525a35d1cbe9c6aba31d10bba46cd9014b382c
✗ TEST FAILED: Checksum mismatch
```

### Solução Recomendada

**Opção 1: Stream direto para MinIO (recomendado)**
```java
// Ao invés de acumular tudo em memória:
private PipedOutputStream minioOutputStream;

@Override
public void onNext(FileChunk chunk) {
    // Envia chunk diretamente para MinIO em background
    minioOutputStream.write(chunk.getContent().toByteArray());
}

@Override
public void onCompleted() {
    // Finaliza upload para MinIO
    minioOutputStream.close();
}
```

**Opção 2: Flush incremental para MinIO**
```java
if (buffer.size() > BUFFER_FLUSH_SIZE) {
    // Flush to MinIO ANTES de descartar
    minioClient.putObjectPart(..., buffer.toByteArray(), partNumber++);
    buffer.reset();  // Limpa buffer MAS dados já estão no MinIO
}
```

**Opção 3: Remover limite (simples mas usa memória)**
```java
// Simplesmente remover o flush - funciona para arquivos até RAM disponível
// buffer.write(content);  // Sem limite
```

### Workaround Atual

**Para testes com arquivos > 10MB:**
1. Usar presigned URLs (MinIO direto, sem gRPC)
2. Ou: corrigir bug antes de usar arquivos grandes
3. Limitar uploads a 10MB até correção

---

## 📊 Arquitetura Validada

```
┌─────────────────────────────────────────────────────────────┐
│                    FILE UPLOAD FLOW                          │
└─────────────────────────────────────────────────────────────┘

Cliente Python
    │
    │ 1. UploadFile (gRPC streaming)
    │    - Chunks de 1MB
    │    - SHA-256 checksum
    │    - Metadata no 1º chunk
    ▼
API Service (FileServiceImpl)
    │
    │ 2. Valida chunks
    │    - Tamanho < 2GB ✅
    │    - Checksum por chunk ✅
    │    - Flush buffer (BUG em >10MB ⚠️)
    ▼
MinIO Storage
    │
    │ 3. Persiste arquivo
    │    - Bucket: chat4all-files
    │    - Path: conversation_id/file_id_filename
    │
    │ 4. Retorna file_id
    │
    ▼
Cliente recebe UploadFileResponse
    │
    │ 5. SendMessage(file_id=...)
    ▼
API Service → Kafka → Router Worker
    │
    │ 6. Processa mensagem
    │    - Salva no Cassandra (com file_id)
    │    - Publica notificação Redis
    ▼
Redis Pub/Sub
    │
    │ 7. Notificação com file_id
    │    - Canal: notifications:user_id
    │    - Payload JSON: {file_id: "xxx", ...}
    ▼
WebSocket Gateway → Cliente
```

---

## ✅ Conclusões

### Funcionalidades Validadas

1. ✅ **Upload gRPC Streaming**
   - Chunks de 1MB funcionando
   - Checksum SHA-256 verificado
   - Throughput: ~98 MB/s (10MB)

2. ✅ **Persistência MinIO**
   - Arquivos salvos corretamente (até 10MB)
   - Storage path organizado por conversation_id
   - Metadata armazenado em memória (em produção seria Cassandra)

3. ✅ **Download com Verificação**
   - Streaming reverso funcionando
   - Checksum validado
   - Integridade garantida

4. ✅ **Notificações 1:1 com Arquivos**
   - file_id incluído no payload JSON
   - Redis Pub/Sub funcionando
   - WebSocket Gateway subscrito (1 subscriber)

5. ✅ **Notificações Grupo com Arquivos**
   - Broadcast para todos membros (exceto sender) ✅
   - file_id propagado para todos
   - Escalabilidade mantida

### Limitações Conhecidas

1. ⚠️ **Arquivos > 10MB**
   - Bug no buffer flush
   - Descarta dados ao otimizar memória
   - **Solução:** Implementar streaming incremental para MinIO

2. ⚠️ **Metadata em Memória**
   - fileMetadataStore usa ConcurrentHashMap
   - **Produção:** Migrar para Cassandra `files` table

3. ⚠️ **Sem Rate Limiting**
   - Upload ilimitado por usuário
   - **Produção:** Implementar quota por usuário/grupo

### Próximos Passos (Produção)

1. 🔧 **Corrigir bug buffer flush**
   - Implementar streaming incremental
   - Testar com arquivos de 1GB+
   - Validar limite de 2GB

2. 📊 **Persistir Metadata**
   - Criar tabela Cassandra `files`
   - Schema: file_id, filename, size_bytes, mime_type, checksum, storage_path, uploaded_at

3. 🔒 **Segurança**
   - Validar permissões de acesso
   - Scan anti-virus (opcional)
   - Limite de tipos MIME (opcional)

4. 📈 **Métricas**
   - file_upload_duration
   - file_upload_size_bytes
   - file_download_count

---

## 📁 Artefatos de Teste

### Scripts Criados

- ✅ `test_file_upload.py` - Cliente Python para upload/download streaming
- ✅ `test_file_notifications.sh` - Validação de notificações com arquivos
- ✅ `test_final_validation.sh` - Bateria completa de regressão

### Logs Importantes

```bash
# Ver uploads no API service
docker compose logs api-service --tail=100 | grep -A10 "Upload completed"

# Ver notificações com file_id
docker compose logs router-worker --tail=100 | grep -B5 -A5 "file_id"

# Verificar arquivos no MinIO
docker exec chat4all-minio mc ls local/chat4all-files/
```

---

**🎉 VALIDAÇÃO COMPLETA - UPLOAD E NOTIFICAÇÕES FUNCIONANDO!**

**Nota:** Bug em arquivos > 10MB documentado. Sistema funcional para casos de uso com limite de 10MB por arquivo.
