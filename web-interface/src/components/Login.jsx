import { useState } from 'react'
import api from '../services/api'

export default function Login({ onLogin }) {
  const [isLogin, setIsLogin] = useState(true)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')
    setLoading(true)

    try {
      if (isLogin) {
        // Login
        const response = await api.authenticate(username, password)
        onLogin({ username, userId: response.userId }, response.token)
      } else {
        // Registro
        await api.createUser(username, password)
        // Após criar, fazer login automaticamente
        const response = await api.authenticate(username, password)
        onLogin({ username, userId: response.userId }, response.token)
      }
    } catch (err) {
      setError(err.message || 'Erro ao processar sua solicitação')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex items-center justify-center min-h-screen bg-gradient-to-br from-whatsapp-dark to-whatsapp-green">
      <div className="w-full max-w-md p-8 space-y-6 bg-white rounded-2xl shadow-2xl">
        {/* Logo e Título */}
        <div className="text-center">
          <div className="flex justify-center mb-4">
            <div className="w-20 h-20 bg-whatsapp-green rounded-full flex items-center justify-center">
              <svg className="w-12 h-12 text-white" fill="currentColor" viewBox="0 0 24 24">
                <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zm-1-13h2v6h-2zm0 8h2v2h-2z"/>
              </svg>
            </div>
          </div>
          <h1 className="text-3xl font-bold text-gray-900">Chat4All</h1>
          <p className="mt-2 text-sm text-gray-600">
            Sistema de mensageria distribuído
          </p>
        </div>

        {/* Abas Login/Registro */}
        <div className="flex border-b border-gray-200">
          <button
            onClick={() => setIsLogin(true)}
            className={`flex-1 py-3 text-sm font-medium transition-colors ${
              isLogin
                ? 'text-whatsapp-green border-b-2 border-whatsapp-green'
                : 'text-gray-500 hover:text-gray-700'
            }`}
          >
            Entrar
          </button>
          <button
            onClick={() => setIsLogin(false)}
            className={`flex-1 py-3 text-sm font-medium transition-colors ${
              !isLogin
                ? 'text-whatsapp-green border-b-2 border-whatsapp-green'
                : 'text-gray-500 hover:text-gray-700'
            }`}
          >
            Registrar
          </button>
        </div>

        {/* Formulário */}
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label htmlFor="username" className="block text-sm font-medium text-gray-700">
              Usuário
            </label>
            <input
              id="username"
              type="text"
              required
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              className="mt-1 block w-full px-4 py-3 border border-gray-300 rounded-lg shadow-sm focus:ring-whatsapp-green focus:border-whatsapp-green"
              placeholder="Digite seu usuário"
            />
          </div>

          <div>
            <label htmlFor="password" className="block text-sm font-medium text-gray-700">
              Senha
            </label>
            <input
              id="password"
              type="password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="mt-1 block w-full px-4 py-3 border border-gray-300 rounded-lg shadow-sm focus:ring-whatsapp-green focus:border-whatsapp-green"
              placeholder="Digite sua senha"
            />
          </div>

          {error && (
            <div className="p-3 bg-red-50 border border-red-200 rounded-lg">
              <p className="text-sm text-red-600">{error}</p>
            </div>
          )}

          <button
            type="submit"
            disabled={loading}
            className="w-full py-3 px-4 bg-whatsapp-green hover:bg-whatsapp-dark text-white font-medium rounded-lg shadow-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {loading ? 'Processando...' : isLogin ? 'Entrar' : 'Criar Conta'}
          </button>
        </form>

        {/* Info adicional */}
        <div className="text-center text-xs text-gray-500">
          <p>Desenvolvido com gRPC, Kafka e Cassandra</p>
          <p className="mt-1">Sistemas Distribuídos - 2024</p>
        </div>
      </div>
    </div>
  )
}
