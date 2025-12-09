const API_BASE_URL = 'http://localhost:8080/api'
const WS_URL = 'ws://localhost:9095/ws'

class ChatAPI {
  constructor() {
    this.token = null
    this.ws = null
    this.messageHandlers = []
  }

  // ============================================================================
  // AUTENTICAÇÃO
  // ============================================================================

  async createUser(username, password) {
    const response = await fetch(`${API_BASE_URL}/users`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ username, password }),
    })

    if (!response.ok) {
      const error = await response.text()
      throw new Error(error || 'Erro ao criar usuário')
    }

    return await response.json()
  }

  async authenticate(username, password) {
    const response = await fetch(`${API_BASE_URL}/auth`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ username, password }),
    })

    if (!response.ok) {
      const error = await response.text()
      throw new Error(error || 'Erro ao autenticar')
    }

    const data = await response.json()
    this.token = data.token
    return data
  }

  // ============================================================================
  // MENSAGENS
  // ============================================================================

  async sendMessage(recipientId, content, groupId = null) {
    const response = await fetch(`${API_BASE_URL}/messages`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${this.token}`,
      },
      body: JSON.stringify({
        recipientId: recipientId,
        content: content,
        groupId: groupId
      })
    })

    if (!response.ok) {
      const error = await response.text()
      throw new Error(error || 'Erro ao enviar mensagem')
    }

    return await response.json()
  }

  async getMessages(userId, limit = 50) {
    const response = await fetch(
      `${API_BASE_URL}/messages/${userId}?limit=${limit}`,
      {
        headers: {
          'Authorization': `Bearer ${this.token}`,
        },
      }
    )

    if (!response.ok) {
      throw new Error('Erro ao buscar mensagens')
    }

    return await response.json()
  }

  async getConversations() {
    const response = await fetch(`${API_BASE_URL}/conversations`, {
      headers: {
        'Authorization': `Bearer ${this.token}`,
      },
    })

    if (!response.ok) {
      throw new Error('Erro ao buscar conversas')
    }

    return await response.json()
  }

  // ============================================================================
  // GRUPOS
  // ============================================================================

  async createGroup(groupName, memberIds) {
    const response = await fetch(`${API_BASE_URL}/groups`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${this.token}`,
      },
      body: JSON.stringify({
        groupName,
        memberIds,
      }),
    })

    if (!response.ok) {
      const error = await response.text()
      throw new Error(error || 'Erro ao criar grupo')
    }

    return await response.json()
  }

  async getGroups() {
    const response = await fetch(`${API_BASE_URL}/groups`, {
      headers: {
        'Authorization': `Bearer ${this.token}`,
      },
    })

    if (!response.ok) {
      throw new Error('Erro ao buscar grupos')
    }

    return await response.json()
  }

  async addMemberToGroup(groupId, userId) {
    const response = await fetch(`${API_BASE_URL}/groups/${groupId}/members`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${this.token}`,
      },
      body: JSON.stringify({ userId }),
    })

    if (!response.ok) {
      throw new Error('Erro ao adicionar membro ao grupo')
    }

    return await response.json()
  }

  // ============================================================================
  // ARQUIVOS
  // ============================================================================

  async uploadFile(file, recipientId, groupId = null) {
    const formData = new FormData()
    formData.append('file', file)
    if (recipientId) formData.append('recipientId', recipientId)
    if (groupId) formData.append('groupId', groupId)

    const response = await fetch(`${API_BASE_URL}/files/upload`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${this.token}`,
      },
      body: formData,
    })

    if (!response.ok) {
      throw new Error('Erro ao fazer upload do arquivo')
    }

    return await response.json()
  }

  async downloadFile(fileId) {
    const response = await fetch(`${API_BASE_URL}/files/${fileId}`, {
      headers: {
        'Authorization': `Bearer ${this.token}`,
      },
    })

    if (!response.ok) {
      throw new Error('Erro ao baixar arquivo')
    }

    return await response.blob()
  }

  // ============================================================================
  // WEBSOCKET (Notificações em Tempo Real)
  // ============================================================================

  connectWebSocket(userId) {
    if (this.ws) {
      this.ws.close()
    }

    this.ws = new WebSocket(`${WS_URL}?userId=${userId}&token=${this.token}`)

    this.ws.onopen = () => {
      console.log('WebSocket conectado')
    }

    this.ws.onmessage = (event) => {
      try {
        const message = JSON.parse(event.data)
        this.messageHandlers.forEach(handler => handler(message))
      } catch (error) {
        console.error('Erro ao processar mensagem WebSocket:', error)
      }
    }

    this.ws.onerror = (error) => {
      console.error('Erro no WebSocket:', error)
    }

    this.ws.onclose = () => {
      console.log('WebSocket desconectado')
      // Reconectar após 3 segundos
      setTimeout(() => this.connectWebSocket(userId), 3000)
    }
  }

  onMessage(handler) {
    this.messageHandlers.push(handler)
  }

  disconnectWebSocket() {
    if (this.ws) {
      this.ws.close()
      this.ws = null
    }
    this.messageHandlers = []
  }

  // ============================================================================
  // BUSCA DE USUÁRIOS
  // ============================================================================

  async searchUsers(query) {
    const response = await fetch(
      `${API_BASE_URL}/users/search?q=${encodeURIComponent(query)}`,
      {
        headers: {
          'Authorization': `Bearer ${this.token}`,
        },
      }
    )

    if (!response.ok) {
      throw new Error('Erro ao buscar usuários')
    }

    return await response.json()
  }
}

const api = new ChatAPI()
export default api
