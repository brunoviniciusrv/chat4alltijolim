import { useState, useEffect } from 'react'
import Sidebar from './Sidebar'
import ChatWindow from './ChatWindow'
import api from '../services/api'

export default function ChatInterface({ user, token, onLogout }) {
  const [conversations, setConversations] = useState([])
  const [groups, setGroups] = useState([])
  const [selectedChat, setSelectedChat] = useState(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    loadInitialData()
    // Conectar WebSocket para receber mensagens em tempo real
    api.connectWebSocket(user.userId)

    // Listener para novas mensagens
    api.onMessage((message) => {
      console.log('Nova mensagem recebida:', message)
      // Atualizar conversas quando receber mensagem
      loadConversations()
    })

    return () => {
      api.disconnectWebSocket()
    }
  }, [user])

  const loadInitialData = async () => {
    try {
      await Promise.all([
        loadConversations(),
        loadGroups()
      ])
    } catch (error) {
      console.error('Erro ao carregar dados:', error)
    } finally {
      setLoading(false)
    }
  }

  const loadConversations = async () => {
    try {
      const data = await api.getConversations()
      setConversations(data || [])
    } catch (error) {
      console.error('Erro ao carregar conversas:', error)
      setConversations([])
    }
  }

  const loadGroups = async () => {
    try {
      const data = await api.getGroups()
      setGroups(data || [])
    } catch (error) {
      console.error('Erro ao carregar grupos:', error)
      setGroups([])
    }
  }

  const handleSelectChat = (chat) => {
    setSelectedChat(chat)
  }

  const handleNewConversation = () => {
    loadConversations()
  }

  const handleNewGroup = () => {
    loadGroups()
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center h-screen">
        <div className="text-center">
          <div className="w-16 h-16 border-4 border-whatsapp-green border-t-transparent rounded-full animate-spin mx-auto"></div>
          <p className="mt-4 text-gray-600">Carregando...</p>
        </div>
      </div>
    )
  }

  return (
    <div className="flex h-screen bg-gray-100">
      {/* Sidebar com lista de conversas */}
      <Sidebar
        user={user}
        conversations={conversations}
        groups={groups}
        selectedChat={selectedChat}
        onSelectChat={handleSelectChat}
        onNewConversation={handleNewConversation}
        onNewGroup={handleNewGroup}
        onLogout={onLogout}
      />

      {/* Janela de chat */}
      <ChatWindow
        user={user}
        selectedChat={selectedChat}
        onMessageSent={loadConversations}
      />
    </div>
  )
}
