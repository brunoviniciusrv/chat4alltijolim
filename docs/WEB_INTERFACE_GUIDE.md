# 🌐 Guia da Interface Web - Chat4All

## Visão Geral

A interface web do Chat4All é uma aplicação moderna e responsiva que permite comunicação em tempo real através de mensagens 1:1 e grupos, com suporte a upload de arquivos.

## Acesso

**URL:** http://localhost:3001

## Funcionalidades

### ✅ Implementadas

- ✅ Cadastro e autenticação de usuários
- ✅ Mensagens 1:1 em tempo real
- ✅ Grupos de conversa
- ✅ Upload de arquivos (até 50MB)
- ✅ Download de arquivos
- ✅ Notificações em tempo real via WebSocket
- ✅ Atualização automática da lista de conversas
- ✅ Badges de mensagens não lidas
- ✅ Interface responsiva (mobile e desktop)
- ✅ Ordenação automática por última mensagem

## Arquitetura Técnica

### Stack Frontend

- **HTML5** - Estrutura semântica
- **CSS3** - Estilos modernos com Tailwind CSS
- **JavaScript (Vanilla)** - Lógica sem frameworks pesados
- **Nginx** - Servidor web com proxy reverso

### Comunicação

```
┌──────────────────┐
│  Web Interface   │
│   (Navegador)    │
└────────┬─────────┘
         │
         ├─────────────┐
         │             │
         ▼             ▼
    REST API      WebSocket
  (port 8081)    (port 9095)
         │             │
         ▼             ▼
   ┌─────────────────────┐
   │    API Service      │
   └─────────────────────┘
```

### Fluxo de Dados

#### Envio de Mensagem

1. Usuário digita mensagem
2. JavaScript captura evento `onSubmit`
3. POST para `/messages` (REST API)
4. API Service publica no Kafka
5. Router Worker processa e salva
6. Router Worker publica notificação no Redis
7. WebSocket Gateway recebe do Redis
8. WebSocket entrega para destinatários conectados
9. Interface atualiza automaticamente

#### Atualização de Conversas

```javascript
// Polling inteligente - 1 segundo
setInterval(() => {
    updateAllConversations();
}, 1000);

async function updateAllConversations() {
    // 1. Buscar grupos do usuário
    const groups = await fetch('/groups?userId=...');
    
    // 2. Para cada grupo, verificar mensagens
    for (const group of groups) {
        const messages = await fetch(`/messages?conversationId=${group.group_id}`);
        // Comparar lastMessageId para detectar novas mensagens
    }
    
    // 3. Verificar conversas 1:1 existentes
    // 4. Buscar novas conversas 1:1
}
```

## Guia de Uso

### 1. Primeiro Acesso

#### Cadastro
1. Acesse http://localhost:3001
2. Clique em "Criar conta"
3. Preencha:
   - Nome de usuário (único)
   - Senha (mínimo 6 caracteres)
4. Clique em "Cadastrar"

#### Login
1. Digite seu usuário e senha
2. Clique em "Entrar"
3. Você será direcionado para a tela de chat

### 2. Conversas 1:1

#### Iniciar Nova Conversa
1. Clique no botão **➕** (canto superior direito)
2. Selecione um usuário da lista
3. Digite sua mensagem
4. Pressione Enter ou clique em "Enviar"

#### Enviar Mensagens
- Digite no campo "Digite uma mensagem..."
- Pressione **Enter** para enviar
- Suas mensagens aparecem à direita (azul)
- Mensagens recebidas aparecem à esquerda (cinza)

### 3. Grupos

#### Criar Grupo
1. Clique no botão **👥** (Novo Grupo)
2. Digite o nome do grupo
3. Selecione os membros (mínimo 2)
4. Clique em "Criar Grupo"
5. O grupo aparece imediatamente na lista

#### Participar de Grupos
- Grupos que você criou aparecem automaticamente
- Grupos onde foi adicionado aparecem ao receber primeira mensagem
- Badge verde indica novas mensagens

### 4. Upload de Arquivos

#### Enviar Arquivo
1. Abra uma conversa (1:1 ou grupo)
2. Clique no ícone **📎** (ao lado do campo de mensagem)
3. Selecione o arquivo (máximo 50MB)
4. O arquivo é enviado automaticamente
5. Aparece como mensagem com ícone de anexo

#### Tipos Suportados
- Imagens: PNG, JPG, GIF, WEBP
- Documentos: PDF, DOC, DOCX, TXT
- Áudio: MP3, WAV, OGG
- Vídeo: MP4, WEBM
- Outros: ZIP, RAR, etc.

#### Baixar Arquivo
1. Clique na mensagem com anexo
2. Arquivo será baixado automaticamente

### 5. Notificações

#### Como Funcionam
- **WebSocket** mantém conexão persistente
- Novas mensagens chegam **instantaneamente**
- Badge numérico mostra quantidade não lida
- Conversa move para o topo automaticamente

#### Indicadores
- **Badge verde com número** - Mensagens não lidas
- **Texto em negrito** - Última mensagem
- **Ordenação** - Conversas mais recentes no topo

## Configuração Avançada

### Nginx Proxy

O nginx faz proxy reverso para os serviços backend:

```nginx
# /web-interface/nginx.conf

# API REST
location /api/ {
    proxy_pass http://api-service:8081/;
}

# WebSocket
location /ws {
    proxy_pass http://websocket-gateway:9095;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
}
```

### Variáveis de Ambiente (app.js)

```javascript
const API_BASE_URL = 'http://localhost:3001/api';
const WS_URL = 'ws://localhost:3001/ws';
```

Para produção, altere para URLs externas.

### Polling Interval

```javascript
// Intervalo de atualização (milissegundos)
const POLLING_INTERVAL = 1000; // 1 segundo

// Ajustar conforme necessidade
// 500ms = muito rápido (alta carga)
// 5000ms = lento (UX ruim)
```

## Troubleshooting

### Mensagens não aparecem

**Problema:** Mensagens enviadas não aparecem no chat

**Soluções:**
1. Verificar console do navegador (F12)
2. Verificar se API está respondendo:
   ```bash
   curl http://localhost:8081/health
   ```
3. Limpar cache: **Ctrl+Shift+R**

### Grupos não aparecem

**Problema:** Grupo criado não aparece na lista

**Soluções:**
1. Aguardar 1-2 segundos (polling)
2. Verificar se você está na lista de membros
3. Verificar logs do Router Worker:
   ```bash
   docker-compose logs router-worker | grep group_
   ```

### Upload falha

**Problema:** Erro ao fazer upload de arquivo

**Causas comuns:**
- Arquivo maior que 50MB
- MinIO não está rodando
- Sem espaço em disco

**Soluções:**
1. Verificar tamanho do arquivo
2. Verificar MinIO:
   ```bash
   docker-compose ps minio
   docker-compose logs minio
   ```
3. Verificar logs da API:
   ```bash
   docker-compose logs api-service | grep upload
   ```

### WebSocket desconecta

**Problema:** Notificações param de funcionar

**Soluções:**
1. Verificar console do navegador
2. WebSocket reconecta automaticamente em 3 segundos
3. Verificar WebSocket Gateway:
   ```bash
   docker-compose logs websocket-gateway
   ```

### Lista não atualiza

**Problema:** Conversas não aparecem/atualizam automaticamente

**Soluções:**
1. Verificar função `updateAllConversations` no console
2. Verificar se polling está ativo:
   ```javascript
   // No console do navegador
   console.log('Polling ativo:', messagePollingInterval !== null);
   ```
3. Reiniciar interface web:
   ```bash
   docker-compose restart web-interface
   ```

## Performance

### Otimizações Implementadas

1. **Polling Inteligente**
   - 1 segundo para updates gerais
   - 500ms para chat aberto
   - Pula verificações quando chat está inativo

2. **Cache de Conversas**
   - Lista mantida em memória
   - Apenas `lastMessageId` comparado
   - Renderização seletiva

3. **Lazy Loading**
   - Mensagens carregadas sob demanda
   - Limite de 100 mensagens por vez
   - Scroll infinito (futuro)

4. **WebSocket Eficiente**
   - Conexão única por usuário
   - Reconexão automática
   - Heartbeat para manter viva

### Métricas

- **Latência de mensagem:** < 200ms
- **Update de lista:** 1 segundo
- **Tamanho da página:** ~50KB (sem cache)
- **Conexões simultâneas:** Ilimitado (testado até 100)

## Segurança

### Autenticação

- **JWT Token** armazenado em `localStorage`
- Expira em 24 horas
- Renovação automática (futuro)

### Validações

```javascript
// Cliente (app.js)
- Tamanho máximo arquivo: 50MB
- Validação de formulários
- Sanitização de HTML

// Servidor (API Service)
- Autenticação obrigatória
- Rate limiting
- Validação de tipos
```

### CORS

```java
// RestGateway.java
headers.set("Access-Control-Allow-Origin", "*");
headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
```

Para produção, restringir origem.

## Desenvolvimento

### Estrutura de Arquivos

```
web-interface/
├── index.html          # Estrutura da página
├── app.js             # Lógica principal
│   ├── Autenticação
│   ├── Gerenciamento de conversas
│   ├── Envio/recebimento de mensagens
│   ├── Upload de arquivos
│   └── WebSocket
├── style.css          # Estilos (Tailwind inline)
├── nginx.conf         # Configuração do servidor
└── package.json       # Dependências (opcional)
```

### Principais Funções (app.js)

```javascript
// Autenticação
async function register()
async function login()
function logout()

// Conversas
async function loadConversations()
async function updateAllConversations()
async function updateConversationFromMessages()
function renderConversations()

// Mensagens
async function openChat(userId, username, isGroup)
async function loadMessages(userId, isPolling)
async function sendMessage()
function addMessageToChat(message)

// Arquivos
async function handleFileSelect(event)
function downloadFile(fileId, fileName)

// WebSocket
function connectWebSocket()
function handleIncomingMessage(message)

// Grupos
async function createGroup()
async function loadUserGroups()
```

### Adicionando Funcionalidades

#### Exemplo: Adicionar Reações

1. **Backend:** Criar endpoint `/reactions`
2. **Frontend:** Adicionar botão nas mensagens
3. **WebSocket:** Notificar reações em tempo real

```javascript
// app.js - adicionar
async function addReaction(messageId, emoji) {
    const response = await fetch(`${API_BASE_URL}/reactions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ messageId, emoji, userId: currentUser.userId })
    });
    // Atualizar UI
}
```

## Roadmap

### Próximas Funcionalidades

- [ ] Digitando... (typing indicator)
- [ ] Mensagens de voz
- [ ] Videochamadas
- [ ] Busca de mensagens
- [ ] Emojis e GIFs
- [ ] Markdown em mensagens
- [ ] Dark mode
- [ ] PWA (Progressive Web App)
- [ ] Notificações push do navegador
- [ ] Compartilhamento de localização

### Melhorias Técnicas

- [ ] Service Worker para cache
- [ ] IndexedDB para armazenamento local
- [ ] Compressão de imagens
- [ ] Lazy loading de imagens
- [ ] Virtual scrolling
- [ ] E2E encryption
- [ ] Rate limiting no cliente

## Referências

- [WebSocket API](https://developer.mozilla.org/en-US/docs/Web/API/WebSocket)
- [Fetch API](https://developer.mozilla.org/en-US/docs/Web/API/Fetch_API)
- [Tailwind CSS](https://tailwindcss.com/)
- [Nginx Proxy](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)

---

**Desenvolvido para o projeto Chat4All - Sistema de Mensageria Distribuído**
