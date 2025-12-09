#!/bin/bash

# Quick Start Script - Chat4All Demo
# Prepara o sistema para apresentação

set -e

echo "=========================================="
echo "🚀 Chat4All - Preparação para Demo"
echo "=========================================="

# Check if Docker is running
echo ""
echo "1️⃣  Verificando Docker..."
if ! docker info > /dev/null 2>&1; then
    echo "❌ Docker não está rodando. Por favor, inicie o Docker."
    exit 1
fi
echo "✅ Docker está rodando"

# Navigate to project directory
cd "$(dirname "$0")"
echo ""
echo "2️⃣  Diretório do projeto:"
pwd

# Start services
echo ""
echo "3️⃣  Iniciando serviços..."
docker-compose up -d

# Wait for API service to be ready
echo ""
echo "4️⃣  Aguardando serviços inicializarem..."
echo "    (Isso pode levar 30-60 segundos)"

for i in {1..60}; do
    if docker-compose logs api-service 2>/dev/null | grep -q "Started"; then
        echo "    ✅ API Service está pronto!"
        break
    fi
    if [ $i -eq 60 ]; then
        echo "    ⚠️  Timeout aguardando API. Verifique os logs:"
        echo "    docker-compose logs api-service"
        exit 1
    fi
    echo -n "."
    sleep 1
done

# Check service health
echo ""
echo "5️⃣  Verificando status dos serviços..."
docker-compose ps

# Run validation test
echo ""
echo "6️⃣  Executando teste de validação..."
cd client-cli
if python3 test_cli_validation.py; then
    echo ""
    echo "=========================================="
    echo "✅ SISTEMA PRONTO PARA DEMO!"
    echo "=========================================="
    echo ""
    echo "📋 Próximos passos:"
    echo ""
    echo "   Terminal 1:"
    echo "   $ cd client-cli"
    echo "   $ python3 main.py"
    echo ""
    echo "   Terminal 2 (nova aba/janela):"
    echo "   $ cd client-cli"
    echo "   $ python3 main.py"
    echo ""
    echo "📖 Guia completo:"
    echo "   - CLI_IMPROVEMENTS.md"
    echo "   - demo_instructions.md"
    echo ""
    echo "=========================================="
else
    echo ""
    echo "❌ Teste de validação falhou!"
    echo "Por favor, verifique os logs:"
    echo "docker-compose logs"
    exit 1
fi
