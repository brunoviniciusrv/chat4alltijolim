// =============================================================================
// CONFIGURAÇÃO E ESTADO GLOBAL
// =============================================================================

const API_BASE_URL = '/api';  // Usando proxy do Nginx
const WS_URL = 'ws://localhost:9095/ws';

let currentUser = null;
let currentToken = null;
let currentChat = null;
let conversations = [];
let ws = null;
let messagePollingInterval = null;
let selectedGroupMembers = new Map(); // Para rastrear membros selecionados do grupo (userId -> username)

// =============================================================================
// INICIALIZAÇÃO
// =============================================================================

document.addEventListener('DOMContentLoaded', () => {
    console.log('[INIT] DOMContentLoaded fired');
    
    // Setup event listeners FIRST
    setupEventListeners();
    console.log('[INIT] Event listeners setup complete');
    
    // Verificar se há sessão salva
    const savedToken = localStorage.getItem('chat4all_token');
    const savedUser = localStorage.getItem('chat4all_user');
    
    if (savedToken && savedUser) {
        console.log('[INIT] Found saved session');
        currentToken = savedToken;
        currentUser = JSON.parse(savedUser);
        showChatInterface();
    }
});

function setupEventListeners() {
    console.log('[SETUP] Setting up event listeners');
    
    // Login/Register tabs
    document.querySelectorAll('.tab').forEach(tab => {
        tab.addEventListener('click', () => {
            document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
            tab.classList.add('active');
            document.getElementById('authType').value = tab.dataset.tab;
            document.getElementById('authBtn').textContent = 
                tab.dataset.tab === 'login' ? 'Entrar' : 'Criar Conta';
        });
    });
    
    // Auth form
    const authForm = document.getElementById('authForm');
    console.log('[SETUP] authForm element:', authForm);
    if (authForm) {
        authForm.addEventListener('submit', handleAuth);
        console.log('[SETUP] Auth form event listener attached');
    } else {
        console.error('[SETUP] authForm not found!');
    }
    
    // Chat interface buttons
    document.getElementById('newChatBtn')?.addEventListener('click', showNewChatModal);
    document.getElementById('newGroupBtn')?.addEventListener('click', showNewGroupModal);
    document.getElementById('refreshBtn')?.addEventListener('click', loadConversations);
    document.getElementById('logoutBtn')?.addEventListener('click', logout);
    
    // Modal close buttons
    document.querySelectorAll('.close-btn').forEach(btn => {
        btn.addEventListener('click', () => closeModal(btn.dataset.modal));
    });
    
    // User search input
    document.getElementById('userSearchInput')?.addEventListener('input', debounce(searchUsers, 500));
    
    // Group member search
    document.getElementById('groupMemberSearchInput')?.addEventListener('input', debounce(searchGroupMembers, 500));
    
    // Create group button
    document.getElementById('createGroupBtn')?.addEventListener('click', createGroup);
    
    // Search conversations
    document.getElementById('searchInput')?.addEventListener('input', filterConversations);
}

// =============================================================================
// AUTENTICAÇÃO
// =============================================================================

async function handleAuth(e) {
    console.log('[AUTH] Form submitted');
    e.preventDefault();
    console.log('[AUTH] Default prevented');
    
    const username = document.getElementById('username').value.trim();
    const password = document.getElementById('password').value;
    const authType = document.getElementById('authType').value;
    const errorContainer = document.getElementById('errorContainer');
    const authBtn = document.getElementById('authBtn');
    
    console.log('[AUTH] Type:', authType, 'Username:', username);
    
    errorContainer.innerHTML = '';
    authBtn.disabled = true;
    authBtn.textContent = 'Processando...';
    
    try {
        if (authType === 'register') {
            console.log('[AUTH] Creating user...');
            await createUser(username, password);
            console.log('[AUTH] User created successfully');
        }
        
        console.log('[AUTH] Authenticating...');
        // Fazer login
        const authData = await authenticate(username, password);
        console.log('[AUTH] Authenticated:', authData);
        
        currentUser = { username, userId: authData.userId };
        currentToken = authData.token;
        
        localStorage.setItem('chat4all_token', currentToken);
        localStorage.setItem('chat4all_user', JSON.stringify(currentUser));
        
        showChatInterface();
        
    } catch (error) {
        console.error('[AUTH] Error:', error);
        errorContainer.innerHTML = `<div class="error-message">${error.message}</div>`;
    } finally {
        console.log('[AUTH] Finally block');
        authBtn.disabled = false;
        authBtn.textContent = authType === 'login' ? 'Entrar' : 'Criar Conta';
    }
}

async function createUser(username, password) {
    console.log('[CREATE_USER] Starting, username:', username);
    
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 10000); // 10s timeout
    
    try {
        const response = await fetch(`${API_BASE_URL}/users`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ 
                username, 
                password,
                email: `${username}@chat4all.local`
            }),
            signal: controller.signal
        });
        
        clearTimeout(timeoutId);
        console.log('[CREATE_USER] Response status:', response.status);
        
        if (!response.ok) {
            const error = await response.text();
            console.error('[CREATE_USER] Error:', error);
            throw new Error(error || 'Erro ao criar usuário');
        }
        
        const result = await response.json();
        console.log('[CREATE_USER] Success:', result);
        return result;
    } catch (error) {
        clearTimeout(timeoutId);
        if (error.name === 'AbortError') {
            console.error('[CREATE_USER] Timeout after 10s');
            throw new Error('Timeout: servidor não respondeu em 10 segundos');
        }
        console.error('[CREATE_USER] Fetch error:', error);
        throw error;
    }
}

async function authenticate(username, password) {
    console.log('[AUTHENTICATE] Starting, username:', username);
    const response = await fetch(`${API_BASE_URL}/auth`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password })
    });
    
    console.log('[AUTHENTICATE] Response status:', response.status);
    
    if (!response.ok) {
        const error = await response.text();
        console.error('[AUTHENTICATE] Error:', error);
        throw new Error(error || 'Usuário ou senha inválidos');
    }
    
    const result = await response.json();
    console.log('[AUTHENTICATE] Success:', result);
    return result;
}

function logout() {
    localStorage.removeItem('chat4all_token');
    localStorage.removeItem('chat4all_user');
    
    if (ws) {
        ws.close();
    }
    
    if (messagePollingInterval) {
        clearInterval(messagePollingInterval);
    }
    
    currentUser = null;
    currentToken = null;
    currentChat = null;
    
    document.getElementById('chatInterface').style.display = 'none';
    document.getElementById('loginScreen').style.display = 'flex';
}

// =============================================================================
// INTERFACE DE CHAT
// =============================================================================

function showChatInterface() {
    document.getElementById('loginScreen').style.display = 'none';
    document.getElementById('chatInterface').style.display = 'block';
    
    // Atualizar informações do usuário
    const avatar = document.getElementById('currentUserAvatar');
    const name = document.getElementById('currentUserName');
    
    avatar.textContent = currentUser.username.charAt(0).toUpperCase();
    name.textContent = currentUser.username;
    
    // Carregar dados iniciais
    loadConversations();
    loadUserGroups(); // Carregar grupos do usuário
    
    // WebSocket desabilitado temporariamente devido a problema de conexão
    // connectWebSocket();
    
    // Polling global para verificar novas conversas e mensagens não lidas (500ms para tempo real)
    let pollCounter = 0;
    messagePollingInterval = setInterval(() => {
        pollCounter++;
        
        // Se há chat aberto, atualizar mensagens dele (a cada 500ms)
        if (currentChat) {
            loadMessages(currentChat.userId, true);
        }
        
        // Verificar novas mensagens em TODAS as conversas (a cada 2 ciclos = 1 segundo)
        if (pollCounter % 2 === 0) {
            updateAllConversations();
        }
    }, 500);
}

// =============================================================================
// ATUALIZAÇÃO AUTOMÁTICA DE CONVERSAS
// =============================================================================

async function updateAllConversations() {
    try {
        // 1. Atualizar grupos do usuário
        const groupsResponse = await fetch(`${API_BASE_URL}/groups?userId=${currentUser.userId}`, {
            headers: { 'Authorization': `Bearer ${currentToken}` }
        });
        
        if (groupsResponse.ok) {
            const groupsData = await groupsResponse.json();
            
            for (const group of groupsData.groups || []) {
                await updateConversationFromMessages(group.group_id, group.name, true);
            }
        }
        
        // 2. Verificar conversas 1:1 existentes
        for (const conv of conversations.filter(c => !c.isGroup)) {
            await updateConversationFromMessages(conv.userId, conv.username, false);
        }
        
        // 3. Verificar se há NOVAS conversas 1:1 (usuários que enviaram mensagem)
        const usersResp = await fetch(`${API_BASE_URL}/users`, {
            headers: { 'Authorization': `Bearer ${currentToken}` }
        });
        
        if (usersResp.ok) {
            const allUsers = await usersResp.json();
            
            // Verificar apenas usuários que ainda NÃO estão na lista
            for (const user of allUsers) {
                if (user.userId === currentUser.userId) continue;
                
                // Pular se já existe na lista
                if (conversations.find(c => c.userId === user.userId)) continue;
                
                // Verificar se há mensagens com esse usuário
                const conversationId = [currentUser.userId, user.userId].sort().join('_');
                const messagesResp = await fetch(`${API_BASE_URL}/messages?conversationId=${conversationId}`);
                
                if (messagesResp.ok) {
                    const messages = await messagesResp.json();
                    
                    if (messages.length > 0) {
                        // Há mensagens! Criar conversa
                        await updateConversationFromMessages(user.userId, user.username, false);
                    }
                }
            }
        }
        
    } catch (error) {
        // Silenciar erros de polling para não poluir console
    }
}

async function updateConversationFromMessages(userId, username, isGroup) {
    try {
        const conversationId = isGroup ? userId : [currentUser.userId, userId].sort().join('_');
        const response = await fetch(`${API_BASE_URL}/messages?conversationId=${conversationId}`);
        
        if (!response.ok) return;
        
        const messages = await response.json();
        if (!messages || messages.length === 0) return;
        
        const lastMsg = messages[messages.length - 1];
        
        // Encontrar ou criar conversa na lista
        let conv = conversations.find(c => c.userId === userId);
        
        if (!conv) {
            // Nova conversa - adicionar
            conv = {
                userId: userId,
                username: username,
                lastMessage: '',
                isGroup: isGroup,
                unreadCount: 0,
                lastReadTimestamp: 0,
                lastMessageId: null
            };
            conversations.unshift(conv);
        }
        
        // Verificar se há mensagem nova (comparar com última conhecida)
        if (conv.lastMessageId !== lastMsg.message_id) {
            conv.lastMessageId = lastMsg.message_id;
            conv.lastMessage = lastMsg.content.substring(0, 30) + (lastMsg.content.length > 30 ? '...' : '');
            
            // Contar não lidas (mensagens que não são minhas e são mais recentes que a última leitura)
            const unreadMessages = messages.filter(m => 
                m.sender_id !== currentUser.userId && 
                (!conv.lastReadTimestamp || m.timestamp > conv.lastReadTimestamp)
            );
            
            if (unreadMessages.length > 0) {
                conv.unreadCount = unreadMessages.length;
                
                // Mover para o topo se não é o chat aberto
                if (!currentChat || currentChat.userId !== userId) {
                    conversations = [conv, ...conversations.filter(c => c.userId !== userId)];
                }
            }
            
            renderConversations();
        }
        
    } catch (error) {
        // Silenciar erros
    }
}

// =============================================================================
// WEBSOCKET
// =============================================================================

function connectWebSocket() {
    if (ws) {
        ws.close();
    }
    
    ws = new WebSocket(`${WS_URL}?userId=${currentUser.userId}&token=${currentToken}`);
    
    ws.onopen = () => {
        console.log('✅ WebSocket conectado');
    };
    
    ws.onmessage = (event) => {
        try {
            const message = JSON.parse(event.data);
            console.log('📨 Nova mensagem via WebSocket:', message);
            
            // Se a mensagem é da conversa atual, adicionar diretamente ao chat
            if (currentChat && message.sender_id === currentChat.userId) {
                addMessageToChat(message);
                // Também atualizar a lista (última mensagem)
                const conv = conversations.find(c => c.userId === currentChat.userId);
                if (conv) {
                    conv.lastMessage = message.content.substring(0, 30) + (message.content.length > 30 ? '...' : '');
                    renderConversations();
                }
            } else {
                // Mensagem de outra conversa ou nenhuma conversa aberta
                handleIncomingMessage(message);
            }
        } catch (error) {
            console.error('Erro ao processar mensagem WebSocket:', error);
        }
    };
    
    ws.onerror = (error) => {
        console.error('❌ Erro no WebSocket:', error);
    };
    
    ws.onclose = () => {
        console.log('🔌 WebSocket desconectado. Reconectando em 3s...');
        setTimeout(connectWebSocket, 3000);
    };
}

// =============================================================================
// CONVERSAS
// =============================================================================

async function loadConversations() {
    try {
        const chatList = document.getElementById('conversationsList');
        
        if (!conversations || conversations.length === 0) {
            chatList.innerHTML = `
                <div class="loading">
                    <p>Nenhuma conversa ainda</p>
                    <p style="font-size: 12px; margin-top: 10px;">
                        Clique em ➕ para iniciar uma nova conversa
                    </p>
                </div>
            `;
        } else {
            renderConversations();
        }
    } catch (error) {
        console.error('Erro ao carregar conversas:', error);
    }
}

async function loadUserGroups() {
    try {
        console.log('[LoadGroups] Buscando grupos do usuário:', currentUser.userId);
        const response = await fetch(`${API_BASE_URL}/groups?userId=${currentUser.userId}`, {
            headers: {
                'Authorization': `Bearer ${currentToken}`
            }
        });
        
        if (!response.ok) {
            console.error('[LoadGroups] Erro ao buscar grupos:', response.status);
            return;
        }
        
        const data = await response.json();
        console.log('[LoadGroups] Grupos recebidos:', data.groups);
        
        // Adicionar grupos à lista de conversas (evitar duplicatas)
        if (data.groups && data.groups.length > 0) {
            for (const group of data.groups) {
                const exists = conversations.find(c => c.userId === group.group_id);
                if (!exists) {
                    conversations.unshift({
                        userId: group.group_id,
                        username: group.name,
                        lastMessage: 'Grupo',
                        isGroup: true,
                        unreadCount: 0
                    });
                }
            }
            renderConversations();
        }
    } catch (error) {
        console.error('[LoadGroups] Erro ao carregar grupos:', error);
    }
}

async function handleIncomingMessage(message) {
    console.log('[DEBUG handleIncomingMessage] Processando mensagem:', message);
    console.log('[DEBUG handleIncomingMessage] Conversas atuais:', conversations.length);
    
    // Encontrar ou criar a conversa do remetente
    let conv = conversations.find(c => c.userId === message.sender_id);
    
    if (!conv) {
        console.log('[DEBUG handleIncomingMessage] Conversa não existe, criando nova para sender_id:', message.sender_id);
        // Nova conversa - buscar dados do usuário
        try {
            const response = await fetch(`${API_BASE_URL}/users`);
            const users = await response.json();
            console.log('[DEBUG handleIncomingMessage] Usuários disponíveis:', users.length);
            const sender = users.find(u => u.userId === message.sender_id);
            
            if (sender) {
                console.log('[DEBUG handleIncomingMessage] Remetente encontrado:', sender.username);
                conv = {
                    userId: sender.userId,
                    username: sender.username,
                    lastMessage: '',
                    isGroup: false,
                    unreadCount: 0,
                    lastReadTimestamp: 0
                };
                conversations.unshift(conv); // Adicionar no topo
            } else {
                console.error('[DEBUG handleIncomingMessage] Remetente não encontrado na lista de usuários');
                return;
            }
        } catch (error) {
            console.error('Erro ao buscar dados do remetente:', error);
            return;
        }
    } else {
        console.log('[DEBUG handleIncomingMessage] Conversa já existe:', conv.username);
    }
    
    // Atualizar última mensagem e contador
    conv.lastMessage = message.content.substring(0, 30) + (message.content.length > 30 ? '...' : '');
    conv.unreadCount = (conv.unreadCount || 0) + 1;
    
    console.log('[DEBUG handleIncomingMessage] Atualizando conversa - badge:', conv.unreadCount);
    
    // Mover conversa para o topo
    conversations = [conv, ...conversations.filter(c => c.userId !== conv.userId)];
    console.log('[DEBUG handleIncomingMessage] Renderizando conversas...');
    renderConversations();
}

async function checkGroupMessages() {
    try {
        // Verificar mensagens novas em GRUPOS que não estão abertos
        const groups = conversations.filter(c => c.isGroup);
        
        for (const group of groups) {
            // Pular se é o chat aberto (já está sendo atualizado)
            if (currentChat && currentChat.userId === group.userId) continue;
            
            const messagesResp = await fetch(`${API_BASE_URL}/messages?conversationId=${group.userId}`);
            
            if (messagesResp.ok) {
                const messages = await messagesResp.json();
                
                if (messages.length > 0) {
                    const lastMsg = messages[messages.length - 1];
                    
                    // Verificar se há mensagens novas desde a última vez que verificamos
                    if (!group.lastMessageId || lastMsg.message_id !== group.lastMessageId) {
                        // Atualizar última mensagem
                        group.lastMessage = lastMsg.content.substring(0, 30) + (lastMsg.content.length > 30 ? '...' : '');
                        group.lastMessageId = lastMsg.message_id;
                        
                        // Contar mensagens não lidas (que não são do usuário atual)
                        const unreadMessages = messages.filter(m => 
                            m.sender_id !== currentUser.userId && 
                            (!group.lastReadTimestamp || m.timestamp > group.lastReadTimestamp)
                        );
                        
                        if (unreadMessages.length > 0) {
                            group.unreadCount = unreadMessages.length;
                            
                            // Se há mensagens novas, mover grupo para o topo
                            conversations = [group, ...conversations.filter(c => c.userId !== group.userId)];
                        }
                        
                        renderConversations();
                    }
                }
            }
        }
    } catch (error) {
        console.error('[DEBUG] Erro ao verificar mensagens de grupos:', error);
    }
}

async function checkForNewConversations() {
    try {
        // Otimização: Apenas verificar conversas existentes ou se não há nenhuma
        if (conversations.length === 0) {
            // Primeira vez - buscar usuários que têm mensagens comigo
            const response = await fetch(`${API_BASE_URL}/users`);
            const allUsers = await response.json();
            
            // Limitar verificação aos primeiros 20 usuários para evitar sobrecarga
            const limitedUsers = allUsers.slice(0, 20);
            
            for (const user of limitedUsers) {
                if (user.userId === currentUser.userId) continue;
                
                const conversationId = [currentUser.userId, user.userId].sort().join('_');
                const messagesResp = await fetch(`${API_BASE_URL}/messages?conversationId=${conversationId}`);
                
                if (messagesResp.ok) {
                    const messages = await messagesResp.json();
                    
                    if (messages.length > 0) {
                        const lastMsg = messages[messages.length - 1];
                        conversations.push({
                            userId: user.userId,
                            username: user.username,
                            lastMessage: lastMsg.content.substring(0, 30) + (lastMsg.content.length > 30 ? '...' : ''),
                            isGroup: false,
                            unreadCount: 0,
                            lastReadTimestamp: 0
                        });
                    }
                }
            }
            
            // Renderizar se encontrou conversas
            if (conversations.length > 0) {
                renderConversations();
            }
        } else {
            // Já tem conversas - apenas atualizar mensagens não lidas
            for (const conv of conversations) {
                if (currentChat && currentChat.userId === conv.userId) continue;
                
                const conversationId = [currentUser.userId, conv.userId].sort().join('_');
                const messagesResp = await fetch(`${API_BASE_URL}/messages?conversationId=${conversationId}`);
                
                if (messagesResp.ok) {
                    const messages = await messagesResp.json();
                    
                    if (messages.length > 0) {
                        const lastMsg = messages[messages.length - 1];
                        
                        // Contar mensagens não lidas
                        const unreadMessages = messages.filter(m => 
                            m.sender_id === conv.userId && 
                            (!conv.lastReadTimestamp || m.timestamp > conv.lastReadTimestamp)
                        );
                        
                        if (unreadMessages.length > 0) {
                            conv.unreadCount = unreadMessages.length;
                            conv.lastMessage = lastMsg.content.substring(0, 30) + (lastMsg.content.length > 30 ? '...' : '');
                        }
                    }
                }
            }
        }
        
        renderConversations();
    } catch (error) {
        console.error('[DEBUG] Erro ao verificar novas conversas:', error);
    }
}

function renderConversations() {
    const chatList = document.getElementById('conversationsList');
    
    chatList.innerHTML = conversations.map(conv => `
        <div class="conversation-item ${currentChat && currentChat.userId === conv.userId ? 'active' : ''}" 
             data-user-id="${conv.userId}"
             onclick="openChat('${conv.userId}', '${conv.username}', ${conv.isGroup || false})">
            <div class="conversation-avatar">
                ${conv.username.charAt(0).toUpperCase()}
            </div>
            <div class="conversation-info">
                <div class="conversation-name">${conv.username}</div>
                <div class="conversation-last-message">
                    ${conv.lastMessage || 'Iniciar conversa'}
                </div>
            </div>
            ${conv.unreadCount ? `<div class="unread-badge">${conv.unreadCount}</div>` : ''}
        </div>
    `).join('');
}

function filterConversations() {
    const search = document.getElementById('searchInput').value.toLowerCase();
    const items = document.querySelectorAll('.conversation-item');
    
    items.forEach(item => {
        const name = item.querySelector('.conversation-name').textContent.toLowerCase();
        item.style.display = name.includes(search) ? 'flex' : 'none';
    });
}

// =============================================================================
// CHAT
// =============================================================================

function openChat(userId, username, isGroup = false) {
    currentChat = { userId, username, isGroup };
    
    const chatWindow = document.getElementById('chatWindow');
    
    chatWindow.innerHTML = `
        <div class="chat-header">
            <div class="chat-avatar">${username.charAt(0).toUpperCase()}</div>
            <div class="chat-info">
                <h3>${username}</h3>
            </div>
        </div>
        
        <div class="chat-messages" id="chatMessages">
            <div class="loading">
                <div class="spinner"></div>
                <p>Carregando mensagens...</p>
            </div>
        </div>
        
        <div class="chat-input">
            <input type="file" id="fileInput" class="file-input">
            <button class="icon-btn" onclick="document.getElementById('fileInput').click()" title="Enviar arquivo">📎</button>
            <input type="text" class="message-input" id="messageInput" placeholder="Digite uma mensagem" onkeypress="handleMessageKeyPress(event)">
            <button class="send-btn" onclick="sendMessage()">➤</button>
        </div>
    `;
    
    // Marcar conversa como lida
    const conv = conversations.find(c => c.userId === userId);
    if (conv) {
        conv.unreadCount = 0;
        conv.lastReadTimestamp = Date.now();
        renderConversations();
    }
    
    // Carregar mensagens
    loadMessages(userId);
    
    // Setup file upload
    document.getElementById('fileInput').addEventListener('change', handleFileSelect);
}

async function loadMessages(userId, silent = false) {
    const messagesContainer = document.getElementById('chatMessages');
    
    try {
        // Criar conversationId - para grupos usar o group_id diretamente
        const conversationId = currentChat.isGroup 
            ? userId  // Para grupos, userId já é o group_id
            : [currentUser.userId, userId].sort().join('_');
        
        const response = await fetch(`${API_BASE_URL}/messages?conversationId=${conversationId}`);
        
        if (!response.ok) {
            throw new Error('Erro ao carregar mensagens');
        }
        
        const messages = await response.json();
        
        console.log('[DEBUG] Mensagens recebidas da API:', JSON.stringify(messages, null, 2));
        
        if (!messages || messages.length === 0) {
            if (!silent) {
                messagesContainer.innerHTML = `
                    <div class="empty-state">
                        <p>Nenhuma mensagem ainda</p>
                        <p style="font-size: 12px; margin-top: 10px;">Envie uma mensagem para começar</p>
                    </div>
                `;
            }
        } else {
            const currentMessageCount = messagesContainer.querySelectorAll('.message').length;
            if (!silent || messages.length !== currentMessageCount) {
                renderMessages(messages);
                // Scroll apenas se for nova mensagem
                if (messages.length > currentMessageCount) {
                    scrollToBottom();
                }
            }
        }
        
    } catch (error) {
        console.error('Erro ao carregar mensagens:', error);
        if (!silent) {
            messagesContainer.innerHTML = `
                <div class="loading">
                    <p>Erro ao carregar mensagens</p>
                </div>
            `;
        }
    }
}

function renderMessages(messages) {
    const messagesContainer = document.getElementById('chatMessages');
    
    console.log('[RENDER] Renderizando', messages.length, 'mensagens');
    
    messagesContainer.innerHTML = messages.map((msg, index) => {
        const isSent = msg.sender_id === currentUser.userId; // API retorna sender_id
        const time = new Date(msg.timestamp || Date.now()).toLocaleTimeString('pt-BR', {
            hour: '2-digit',
            minute: '2-digit'
        });
        
        console.log('[RENDER]', index, '- content:', msg.content.substring(0, 30), 'file_id:', msg.file_id);
        
        let content = `
            <div class="message ${isSent ? 'sent' : 'received'}">
                <div class="message-bubble">
                    ${!isSent && currentChat.isGroup ? `<div class="message-sender">${msg.senderUsername || msg.sender_id}</div>` : ''}
                    <div class="message-content">${escapeHtml(msg.content)}</div>
                    ${msg.file_id ? renderFileMessage(msg) : ''}
                    <div class="message-time">${time}</div>
                </div>
            </div>
        `;
        
        return content;
    }).join('');
    
    // Adicionar event listeners para todos os arquivos
    attachFileDownloadListeners();
}

function attachFileDownloadListeners() {
    const messagesContainer = document.getElementById('chatMessages');
    if (!messagesContainer) return;
    
    const fileElements = messagesContainer.querySelectorAll('.file-message');
    console.log('[ATTACH] Encontrados', fileElements.length, 'arquivos para anexar listeners');
    
    fileElements.forEach((fileElement, index) => {
        const fileId = fileElement.getAttribute('data-file-id');
        const fileName = fileElement.getAttribute('data-file-name');
        console.log('[ATTACH]', index, '- fileId:', fileId, 'fileName:', fileName);
        
        // Remover listener anterior se existir
        const newElement = fileElement.cloneNode(true);
        fileElement.parentNode.replaceChild(newElement, fileElement);
        
        // Adicionar novo listener
        newElement.addEventListener('click', () => {
            console.log('[DOWNLOAD] Clicou em arquivo:', fileId, fileName);
            downloadFile(fileId, fileName);
        });
    });
}

function renderFileMessage(msg) {
    return `
        <div class="file-message" data-file-id="${msg.file_id}" data-file-name="${msg.file_name || 'arquivo'}">
            <div class="file-icon">📎</div>
            <div class="file-info">
                <div class="file-name">${msg.file_name || 'arquivo'}</div>
                <div class="file-size">${formatFileSize(msg.file_size || 0)}</div>
            </div>
            <div class="file-download">⬇️</div>
        </div>
    `;
}

function addMessageToChat(message) {
    const messagesContainer = document.getElementById('chatMessages');
    
    if (!messagesContainer) return;
    
    // Remover empty state se existir
    const emptyState = messagesContainer.querySelector('.empty-state');
    if (emptyState) {
        messagesContainer.innerHTML = '';
    }
    
    const isSent = message.sender_id === currentUser.userId;  // API usa snake_case
    const time = new Date(message.timestamp || Date.now()).toLocaleTimeString('pt-BR', {
        hour: '2-digit',
        minute: '2-digit'
    });
    
    const messageHTML = `
        <div class="message ${isSent ? 'sent' : 'received'}">
            <div class="message-bubble">
                ${!isSent && currentChat.isGroup ? `<div class="message-sender">${message.senderUsername || message.sender_id}</div>` : ''}
                <div class="message-content">${escapeHtml(message.content)}</div>
                ${message.file_id ? renderFileMessage(message) : ''}
                <div class="message-time">${time}</div>
            </div>
        </div>
    `;
    
    messagesContainer.insertAdjacentHTML('beforeend', messageHTML);
    
    // Adicionar event listeners para arquivos (usar a mesma função)
    if (message.file_id) {
        attachFileDownloadListeners();
    }
    
    scrollToBottom();
}

function handleMessageKeyPress(event) {
    if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        sendMessage();
    }
}

async function sendMessage() {
    const input = document.getElementById('messageInput');
    const content = input.value.trim();
    
    if (!content || !currentChat) return;
    
    input.value = '';
    input.disabled = true;
    
    try {
        // Criar conversationId - para grupos usar o group_id diretamente
        const conversationId = currentChat.isGroup 
            ? currentChat.userId  // Para grupos, userId já é o group_id
            : [currentUser.userId, currentChat.userId].sort().join('_');
        
        console.log('[DEBUG] Enviando mensagem:', {
            conversationId,
            senderId: currentUser.userId,
            content: content.substring(0, 30)
        });
        
        const response = await fetch(`${API_BASE_URL}/messages`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                conversationId: conversationId,
                content: content,
                senderId: currentUser.userId
            })
        });
        
        console.log('[DEBUG] Response status:', response.status, response.statusText);
        
        if (!response.ok) {
            const errorText = await response.text();
            console.error('[DEBUG] Erro do servidor:', errorText);
            throw new Error('Erro ao enviar mensagem');
        }
        
        const result = await response.json();
        console.log('[DEBUG] Mensagem enviada com sucesso:', result);
        
        // Adicionar mensagem ao chat - usar snake_case
        addMessageToChat({
            message_id: result.messageId,
            sender_id: currentUser.userId,
            content: content,
            timestamp: result.timestamp || Date.now()
        });
        
        // Atualizar última mensagem da conversa e mover para o topo
        const conv = conversations.find(c => c.userId === currentChat.userId);
        if (conv) {
            conv.lastMessage = content.substring(0, 30) + (content.length > 30 ? '...' : '');
            // Mover para o topo da lista
            conversations = [conv, ...conversations.filter(c => c.userId !== conv.userId)];
            renderConversations();
        }
        
    } catch (error) {
        console.error('Erro ao enviar mensagem:', error);
        alert('Erro ao enviar mensagem: ' + error.message);
    } finally {
        input.disabled = false;
        input.focus();
    }
}

// =============================================================================
// ARQUIVOS
// =============================================================================

async function handleFileSelect(event) {
    const file = event.target.files[0];
    if (!file || !currentChat) return;
    
    const maxSize = 50 * 1024 * 1024; // 50MB
    if (file.size > maxSize) {
        alert('Arquivo muito grande. Tamanho máximo: 50MB');
        return;
    }
    
    console.log('[DEBUG Upload] Iniciando upload:', {
        fileName: file.name,
        fileSize: file.size,
        recipientId: currentChat.userId
    });
    
    try {
        // 1. Upload do arquivo
        const uploadResponse = await fetch(`${API_BASE_URL}/files/upload`, {
            method: 'POST',
            headers: {
                'Content-Type': file.type || 'application/octet-stream',
                'Content-Disposition': `attachment; filename="${file.name}"`
            },
            body: file
        });
        
        if (!uploadResponse.ok) {
            throw new Error(`Erro no upload: ${uploadResponse.status}`);
        }
        
        const uploadResult = await uploadResponse.json();
        console.log('[DEBUG Upload] Arquivo enviado:', uploadResult);
        
        // 2. Enviar mensagem com referência ao arquivo
        // Para grupos usar o group_id diretamente
        const conversationId = currentChat.isGroup 
            ? currentChat.userId  // Para grupos, userId já é o group_id
            : [currentUser.userId, currentChat.userId].sort().join('_');
        const content = `📎 ${file.name}`;
        
        const messageResponse = await fetch(`${API_BASE_URL}/messages`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                conversationId: conversationId,
                content: content,
                senderId: currentUser.userId,
                fileId: uploadResult.fileId,
                fileName: file.name,
                fileSize: file.size
            })
        });
        
        if (!messageResponse.ok) {
            throw new Error('Erro ao enviar mensagem');
        }
        
        const messageResult = await messageResponse.json();
        
        // 3. Adicionar mensagem ao chat
        addMessageToChat({
            message_id: messageResult.messageId,
            sender_id: currentUser.userId,
            content: content,
            file_id: uploadResult.fileId,
            file_name: file.name,
            file_size: file.size,
            timestamp: messageResult.timestamp || Date.now()
        });
        
        // 4. Atualizar conversa
        const conv = conversations.find(c => c.userId === currentChat.userId);
        if (conv) {
            conv.lastMessage = content;
            conversations = [conv, ...conversations.filter(c => c.userId !== conv.userId)];
            renderConversations();
        }
        
        // Limpar input
        event.target.value = '';
        
        console.log('[DEBUG Upload] ✅ Arquivo enviado com sucesso!');
        
    } catch (error) {
        console.error('[DEBUG Upload] Erro completo:', error);
        alert('Erro ao enviar arquivo: ' + error.message);
    }
}

async function downloadFile(fileId, fileName) {
    try {
        const response = await fetch(`${API_BASE_URL}/files/${fileId}`, {
            headers: {
                'Authorization': `Bearer ${currentToken}`
            }
        });
        
        if (!response.ok) {
            throw new Error('Erro ao baixar arquivo');
        }
        
        const blob = await response.blob();
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = fileName;
        document.body.appendChild(a);
        a.click();
        window.URL.revokeObjectURL(url);
        document.body.removeChild(a);
        
    } catch (error) {
        console.error('Erro ao baixar arquivo:', error);
        alert('Erro ao baixar arquivo: ' + error.message);
    }
}

// =============================================================================
// NOVA CONVERSA
// =============================================================================

function showNewChatModal() {
    document.getElementById('newChatModal').classList.add('show');
    document.getElementById('userSearchInput').value = '';
    document.getElementById('userSearchResults').innerHTML = `
        <div class="loading">
            <p>Digite o nome do usuário (ex: user2)</p>
        </div>
    `;
}

async function searchUsers() {
    const query = document.getElementById('userSearchInput').value.trim().toLowerCase();
    const resultsContainer = document.getElementById('userSearchResults');
    
    if (!query) {
        resultsContainer.innerHTML = `
            <div class="loading">
                <p>Digite o nome do usuário</p>
            </div>
        `;
        return;
    }
    
    try {
        // Buscar todos os usuários da API
        const response = await fetch(`${API_BASE_URL}/users`);
        const users = await response.json();
        
        // Filtrar usuários que correspondem à busca (exceto o usuário atual)
        const filtered = users.filter(u => 
            u.username.toLowerCase().includes(query) && 
            u.userId !== currentUser.userId
        );
        
        if (filtered.length === 0) {
            resultsContainer.innerHTML = `
                <div class="loading">
                    <p>Nenhum usuário encontrado</p>
                </div>
            `;
            return;
        }
        
        resultsContainer.innerHTML = filtered.map(user => `
            <div class="user-item" onclick="startConversation('${user.userId}', '${user.username}')">
                <div class="user-item-avatar">${user.username.charAt(0).toUpperCase()}</div>
                <div>${user.username}</div>
            </div>
        `).join('');
        
    } catch (error) {
        console.error('Erro ao buscar usuários:', error);
        resultsContainer.innerHTML = `
            <div class="loading">
                <p>Erro ao buscar usuários</p>
            </div>
        `;
    }
}

function startConversation(userId, username) {
    // Adicionar à lista de conversas se não existir
    if (!conversations.find(c => c.userId === userId)) {
        conversations.push({
            userId: userId,
            username: username,
            lastMessage: '',
            isGroup: false,
            unreadCount: 0,
            lastReadTimestamp: Date.now()
        });
        renderConversations();
    }
    
    closeModal('newChatModal');
    openChat(userId, username, false);
}

// =============================================================================
// GRUPOS
// =============================================================================

function showNewGroupModal() {
    selectedGroupMembers.clear();
    updateSelectedMembersList();
    document.getElementById('newGroupModal').classList.add('show');
    document.getElementById('groupNameInput').value = '';
    document.getElementById('groupMemberSearchInput').value = '';
    loadAllUsersForGroup();
}

async function loadAllUsersForGroup() {
    const resultsContainer = document.getElementById('groupMemberSearchResults');
    
    try {
        const response = await fetch(`${API_BASE_URL}/users`);
        if (!response.ok) throw new Error('Erro ao buscar usuários');
        
        const users = await response.json();
        const filteredUsers = users.filter(u => u.userId !== currentUser.userId);
        
        if (filteredUsers.length === 0) {
            resultsContainer.innerHTML = '<div class="loading"><p>Nenhum usuário disponível</p></div>';
            return;
        }
        
        resultsContainer.innerHTML = filteredUsers.map(user => {
            const isSelected = selectedGroupMembers.has(user.userId);
            return `
                <div class="user-item ${isSelected ? 'selected' : ''}" data-user-id="${user.userId}" data-username="${user.username}">
                    <div class="user-item-avatar">${user.username.charAt(0).toUpperCase()}</div>
                    <div class="user-item-info">
                        <div class="user-item-name">${user.username}</div>
                        <div class="user-item-id">${user.userId}</div>
                    </div>
                    <div style="margin-left: auto; font-size: 20px;">
                        ${isSelected ? '✓' : ''}
                    </div>
                </div>
            `;
        }).join('');
        
        // Adicionar event listeners
        resultsContainer.querySelectorAll('.user-item').forEach(item => {
            item.addEventListener('click', () => toggleGroupMember(item));
        });
        
    } catch (error) {
        console.error('[Group] Erro ao carregar usuários:', error);
        resultsContainer.innerHTML = '<div class="loading"><p>Erro ao carregar usuários</p></div>';
    }
}

async function searchGroupMembers(event) {
    const query = event.target.value.trim().toLowerCase();
    const resultsContainer = document.getElementById('groupMemberSearchResults');
    
    if (!query) {
        loadAllUsersForGroup();
        return;
    }
    
    try {
        const response = await fetch(`${API_BASE_URL}/users`);
        if (!response.ok) throw new Error('Erro ao buscar usuários');
        
        const users = await response.json();
        const filteredUsers = users.filter(u => 
            u.userId !== currentUser.userId && 
            u.username.toLowerCase().includes(query)
        );
        
        if (filteredUsers.length === 0) {
            resultsContainer.innerHTML = '<div class="loading"><p>Nenhum usuário encontrado</p></div>';
            return;
        }
        
        resultsContainer.innerHTML = filteredUsers.map(user => {
            const isSelected = selectedGroupMembers.has(user.userId);
            return `
                <div class="user-item ${isSelected ? 'selected' : ''}" data-user-id="${user.userId}" data-username="${user.username}">
                    <div class="user-item-avatar">${user.username.charAt(0).toUpperCase()}</div>
                    <div class="user-item-info">
                        <div class="user-item-name">${user.username}</div>
                        <div class="user-item-id">${user.userId}</div>
                    </div>
                    <div style="margin-left: auto; font-size: 20px;">
                        ${isSelected ? '✓' : ''}
                    </div>
                </div>
            `;
        }).join('');
        
        // Adicionar event listeners
        resultsContainer.querySelectorAll('.user-item').forEach(item => {
            item.addEventListener('click', () => toggleGroupMember(item));
        });
        
    } catch (error) {
        console.error('[Group] Erro na busca:', error);
    }
}

function toggleGroupMember(item) {
    const userId = item.getAttribute('data-user-id');
    const username = item.getAttribute('data-username');
    
    console.log('[toggleGroupMember] userId:', userId, 'username:', username);
    
    if (selectedGroupMembers.has(userId)) {
        selectedGroupMembers.delete(userId);
        item.classList.remove('selected');
        item.querySelector('div[style*="margin-left"]').textContent = '';
        console.log('[toggleGroupMember] Removido. Total:', selectedGroupMembers.size);
    } else {
        selectedGroupMembers.set(userId, username);
        item.classList.add('selected');
        item.querySelector('div[style*="margin-left"]').textContent = '✓';
        console.log('[toggleGroupMember] Adicionado. Total:', selectedGroupMembers.size);
    }
    
    console.log('[toggleGroupMember] selectedGroupMembers:', Array.from(selectedGroupMembers.entries()));
    
    updateSelectedMembersList();
}

function updateSelectedMembersList() {
    const container = document.getElementById('selectedMembersList');
    const count = document.getElementById('selectedMemberCount');
    
    count.textContent = selectedGroupMembers.size;
    
    if (selectedGroupMembers.size === 0) {
        container.innerHTML = '<div style="text-align: center; color: #667781; padding: 10px;">Nenhum membro selecionado</div>';
        return;
    }
    
    // Usar dados do Map diretamente
    const members = Array.from(selectedGroupMembers.entries()).map(([userId, username]) => ({
        userId,
        username
    }));
    
    container.innerHTML = members.map(member => `
        <div class="selected-member-chip">
            <span>${member.username}</span>
            <span class="selected-member-remove" onclick="removeMemberFromGroup('${member.userId}')">×</span>
        </div>
    `).join('');
}

function removeMemberFromGroup(userId) {
    selectedGroupMembers.delete(userId);
    updateSelectedMembersList();
    
    // Atualizar visual na lista
    const item = document.querySelector(`.user-item[data-user-id="${userId}"]`);
    if (item) {
        item.classList.remove('selected');
        item.querySelector('div[style*="margin-left"]').textContent = '';
    }
}

async function createGroup() {
    const groupName = document.getElementById('groupNameInput').value.trim();
    
    if (!groupName) {
        alert('Digite o nome do grupo');
        return;
    }
    
    if (selectedGroupMembers.size === 0) {
        alert('Selecione pelo menos um membro para o grupo');
        return;
    }
    
    const memberIds = Array.from(selectedGroupMembers.keys());
    
    console.log('[createGroup] Criando grupo:', { groupName, memberIds, count: memberIds.length });
    console.log('[createGroup] selectedGroupMembers:', Array.from(selectedGroupMembers.entries()));
    
    try {
        const response = await fetch(`${API_BASE_URL}/groups`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${currentToken}`
            },
            body: JSON.stringify({
                groupName: groupName,
                memberIds: memberIds
            })
        });
        
        if (!response.ok) {
            const error = await response.text();
            throw new Error(error || 'Erro ao criar grupo');
        }
        
        const group = await response.json();
        console.log('[createGroup] Grupo criado com sucesso:', group);
        
        // Adicionar grupo à lista de conversas
        conversations.unshift({
            userId: group.group_id,
            username: groupName,
            lastMessage: 'Grupo criado',
            isGroup: true,
            unreadCount: 0,
            lastReadTimestamp: Date.now()
        });
        
        console.log('[createGroup] Grupo adicionado à lista');
        
        // Renderizar lista
        renderConversations();
        
        // Fechar modal
        closeModal('newGroupModal');
        
        console.log('[createGroup] Concluído!');
        
    } catch (error) {
        console.error('[Group] Erro ao criar grupo:', error);
        alert('Erro ao criar grupo: ' + error.message);
    }
}

// =============================================================================
// MODAL
// =============================================================================

function closeModal(modalId) {
    document.getElementById(modalId).classList.remove('show');
}

// =============================================================================
// UTILIDADES
// =============================================================================

function scrollToBottom() {
    const messagesContainer = document.getElementById('chatMessages');
    if (messagesContainer) {
        messagesContainer.scrollTop = messagesContainer.scrollHeight;
    }
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

function formatFileSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

function debounce(func, wait) {
    let timeout;
    return function executedFunction(...args) {
        const later = () => {
            clearTimeout(timeout);
            func(...args);
        };
        clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
}
