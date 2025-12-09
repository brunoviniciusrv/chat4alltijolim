#!/bin/bash

echo "╔═══════════════════════════════════════════════════════════════════╗"
echo "║                                                                   ║"
echo "║  Chat4All - VALIDAÇÃO COMPLETA DO SISTEMA                        ║"
echo "║                                                                   ║"
echo "╚═══════════════════════════════════════════════════════════════════╝"
echo ""

cd "$(dirname "$0")"

PASSED=0
FAILED=0

test_passed() {
    echo "   ✅ $1"
    ((PASSED++))
}

test_failed() {
    echo "   ❌ $1"
    ((FAILED++))
}

# ============================================================================
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "1️⃣  INFRAESTRUTURA"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# Containers Docker
CONTAINERS=$(docker compose ps --format "{{.Name}}" --filter "status=running" 2>/dev/null | wc -l)
if [ $CONTAINERS -ge 10 ]; then
    test_passed "Containers rodando: $CONTAINERS"
else
    test_failed "Containers insuficientes: $CONTAINERS/10+"
fi

# Kafka
if docker compose exec -T kafka kafka-broker-api-versions --bootstrap-server localhost:9092 &>/dev/null; then
    test_passed "Kafka broker respondendo"
else
    test_failed "Kafka não responde"
fi

# Cassandra
if docker compose exec -T cassandra cqlsh -e "SELECT COUNT(*) FROM chat4all.messages;" &>/dev/null; then
    test_passed "Cassandra acessível"
else
    test_failed "Cassandra não acessível"
fi

# Redis
if docker compose exec -T redis redis-cli ping 2>/dev/null | grep -q PONG; then
    test_passed "Redis respondendo"
else
    test_failed "Redis não responde"
fi

# MinIO
MINIO_STATUS=$(docker compose ps minio --format "{{.State}}" 2>/dev/null)
if [ "$MINIO_STATUS" == "running" ]; then
    test_passed "MinIO rodando"
else
    test_failed "MinIO não rodando"
fi

# ============================================================================
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "2️⃣  SERVIÇOS DE APLICAÇÃO"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# API Service
if timeout 2 grpcurl -plaintext localhost:9091 list &>/dev/null; then
    test_passed "API Service gRPC respondendo"
else
    test_failed "API Service não responde"
fi

# Metrics
if curl -s http://localhost:8080/metrics | grep -q grpc_requests_total; then
    test_passed "API Service métricas disponíveis"
else
    test_failed "Métricas não disponíveis"
fi

# Router Worker
if docker compose ps router-worker --format "{{.State}}" 2>/dev/null | grep -q "running"; then
    test_passed "Router Worker rodando"
else
    test_failed "Router Worker não rodando"
fi

# WebSocket Gateway
if curl -s http://localhost:9095/metrics 2>/dev/null | grep -q "websocket"; then
    test_passed "WebSocket Gateway respondendo"
else
    test_failed "WebSocket Gateway não responde"
fi

# ============================================================================
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "3️⃣  MONITORAMENTO"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# Prometheus
if curl -s http://localhost:9090/-/healthy 2>/dev/null | grep -q "Prometheus Server is Healthy"; then
    test_passed "Prometheus healthy"
else
    test_failed "Prometheus não healthy"
fi

# Prometheus targets
UP_TARGETS=$(curl -s http://localhost:9090/api/v1/targets 2>/dev/null | jq -r '.data.activeTargets[] | select(.health=="up") | .labels.job' | wc -l)
if [ $UP_TARGETS -ge 4 ]; then
    test_passed "Prometheus targets UP: $UP_TARGETS/4"
else
    test_failed "Prometheus targets UP: $UP_TARGETS/4"
fi

# Grafana
if curl -s http://localhost:3000/api/health 2>/dev/null | grep -q "ok"; then
    test_passed "Grafana respondendo"
else
    test_failed "Grafana não responde"
fi

# Dashboards
DASHBOARDS=$(curl -s -u admin:admin http://localhost:3000/api/search?type=dash-db 2>/dev/null | jq '. | length')
if [ "$DASHBOARDS" -ge 1 ]; then
    test_passed "Dashboards Grafana carregados: $DASHBOARDS"
else
    test_failed "Nenhum dashboard carregado"
fi

# Jaeger
if curl -s http://localhost:16686/ 2>/dev/null | grep -q "Jaeger"; then
    test_passed "Jaeger UI acessível"
else
    test_failed "Jaeger não acessível"
fi

# ============================================================================
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "4️⃣  FUNCIONALIDADES CORE"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# E2E Delivery 2
echo "Executando teste E2E (2ª Entrega - Grupos e Notificações)..."
if timeout 120 ./test_e2e_delivery2.sh 2>&1 | grep -q "ALL TESTS PASSED"; then
    test_passed "Teste E2E 2ª Entrega (grupos + notificações)"
else
    test_failed "Teste E2E 2ª Entrega falhou"
fi

# Upload de arquivos
echo ""
echo "Testando upload de arquivo 10MB..."
if timeout 60 python3 test_file_upload.py 2>&1 | grep -q "TEST PASSED: small_file_10MB"; then
    test_passed "Upload de arquivos 10MB funcionando"
else
    test_failed "Upload de arquivos falhou"
fi

# ============================================================================
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "5️⃣  MÉTRICAS E PERFORMANCE"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# Coletar métricas
TOTAL_REQUESTS=$(curl -s http://localhost:8080/metrics 2>/dev/null | grep "^grpc_requests_total" | awk '{print $2}' | cut -d. -f1)
TOTAL_FAILURES=$(curl -s http://localhost:8080/metrics 2>/dev/null | grep "^grpc_requests_failed_total" | awk '{print $2}' | cut -d. -f1)
MAX_LATENCY=$(curl -s http://localhost:8080/metrics 2>/dev/null | grep "^grpc_request_duration_seconds_max" | awk '{print $2}')

if [ ! -z "$TOTAL_REQUESTS" ] && [ "$TOTAL_REQUESTS" -gt 0 ] 2>/dev/null; then
    test_passed "Total requisições: $TOTAL_REQUESTS"
else
    test_failed "Nenhuma requisição registrada"
fi

if [ -z "$TOTAL_FAILURES" ] || [ "$TOTAL_FAILURES" == "0" ]; then
    test_passed "Taxa de sucesso: 100%"
else
    test_failed "Falhas detectadas: $TOTAL_FAILURES"
fi

if [ ! -z "$MAX_LATENCY" ]; then
    LATENCY_MS=$(echo "$MAX_LATENCY * 1000" | bc 2>/dev/null)
    if (( $(echo "$MAX_LATENCY < 0.2" | bc -l 2>/dev/null) )); then
        test_passed "Latência máxima: ${LATENCY_MS}ms (< 200ms)"
    else
        test_failed "Latência máxima: ${LATENCY_MS}ms (> 200ms)"
    fi
fi

# ============================================================================
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "6️⃣  THROUGHPUT (Teste Simples)"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

echo "Executando teste de throughput (10 mensagens)..."
BEFORE=$(curl -s http://localhost:8080/metrics 2>/dev/null | grep "^grpc_requests_total" | awk '{print $2}' | cut -d. -f1)
cd load-tests
export NUM_MESSAGES=10
export CONCURRENCY=5
./simple-throughput-test.sh &>/dev/null
cd ..
sleep 2
AFTER=$(curl -s http://localhost:8080/metrics 2>/dev/null | grep "^grpc_requests_total" | awk '{print $2}' | cut -d. -f1)

if [ ! -z "$AFTER" ] && [ ! -z "$BEFORE" ] && [ "$AFTER" -gt "$BEFORE" ]; then
    DIFF=$((AFTER - BEFORE))
    test_passed "Throughput OK ($DIFF novas requisições processadas)"
else
    test_failed "Throughput test falhou"
fi

# ============================================================================
echo ""
echo "╔═══════════════════════════════════════════════════════════════════╗"
echo "║                                                                   ║"
echo "║  RESUMO DA VALIDAÇÃO                                              ║"
echo "║                                                                   ║"
echo "╚═══════════════════════════════════════════════════════════════════╝"
echo ""
echo "   ✅ Testes aprovados: $PASSED"
echo "   ❌ Testes falhados: $FAILED"
echo ""

if [ $FAILED -eq 0 ]; then
    echo "╔═══════════════════════════════════════════════════════════════════╗"
    echo "║                                                                   ║"
    echo "║  🎉 TODOS OS TESTES PASSARAM! SISTEMA 100% FUNCIONAL! 🎉         ║"
    echo "║                                                                   ║"
    echo "╚═══════════════════════════════════════════════════════════════════╝"
    echo ""
    echo "📊 COMPONENTES VALIDADOS:"
    echo ""
    echo "   ✅ Infraestrutura: Kafka, Cassandra, Redis, MinIO"
    echo "   ✅ Aplicação: API Service, Router Worker, WebSocket Gateway"
    echo "   ✅ Monitoramento: Prometheus, Grafana, Jaeger"
    echo "   ✅ Funcionalidades: Mensagens, Grupos, Notificações, Uploads"
    echo "   ✅ Performance: $TOTAL_REQUESTS requisições, ${LATENCY_MS}ms latência"
    echo ""
    exit 0
else
    echo "╔═══════════════════════════════════════════════════════════════════╗"
    echo "║                                                                   ║"
    echo "║  ⚠️  ALGUNS TESTES FALHARAM - REVISAR COMPONENTES                ║"
    echo "║                                                                   ║"
    echo "╚═══════════════════════════════════════════════════════════════════╝"
    exit 1
fi
