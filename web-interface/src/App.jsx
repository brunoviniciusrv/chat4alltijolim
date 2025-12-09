import { useState, useEffect } from 'react'
import Login from './components/Login'
import ChatInterface from './components/ChatInterface'

function App() {
  const [user, setUser] = useState(null)
  const [token, setToken] = useState(null)

  useEffect(() => {
    // Verificar se há token salvo
    const savedToken = localStorage.getItem('chat4all_token')
    const savedUser = localStorage.getItem('chat4all_user')
    
    if (savedToken && savedUser) {
      setToken(savedToken)
      setUser(JSON.parse(savedUser))
    }
  }, [])

  const handleLogin = (userData, authToken) => {
    setUser(userData)
    setToken(authToken)
    localStorage.setItem('chat4all_token', authToken)
    localStorage.setItem('chat4all_user', JSON.stringify(userData))
  }

  const handleLogout = () => {
    setUser(null)
    setToken(null)
    localStorage.removeItem('chat4all_token')
    localStorage.removeItem('chat4all_user')
  }

  return (
    <div className="h-screen w-screen overflow-hidden bg-gray-100">
      {!user || !token ? (
        <Login onLogin={handleLogin} />
      ) : (
        <ChatInterface user={user} token={token} onLogout={handleLogout} />
      )}
    </div>
  )
}

export default App
