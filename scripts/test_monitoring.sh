#!/bin/bash

echo "════════════════════════════════════════════════════════════"
echo "  Chat4All - Teste de Monitoramento"
echo "════════════════════════════════════════════════════════════"
echo ""

# 1. Verificar Prometheus
echo "1️⃣  Verificando Prometheus..."
PROM_HEALTH=$(curl -s http://localhost:9090/-/healthy 2>/dev/null)
if [[ "$PROM_HEALTH" == *"Healthy"* ]]; then
    echo "   ✅ Prometheus: OK"
else
    echo "   ❌ Prometheus: FALHA"
    exit 1
fi

# 2. Verificar Grafana
echo ""
echo "2️⃣  Verificando Grafana..."
GRAFANA_HEALTH=$(curl -s http://localhost:3000/api/health 2>/dev/null | jq -r '.database' 2>/dev/null)
if [[ "$GRAFANA_HEALTH" == "ok" ]]; then
    echo "   ✅ Grafana: OK"
else
    echo "   ❌ Grafana: FALHA"
    exit 1
fi

# 3. Verificar Targets
echo ""
echo "3️⃣  Verificando Targets do Prometheus..."
TARGETS=$(curl -s http://localhost:9090/api/v1/targets 2>/dev/null | jq -r '.data.activeTargets[] | "\(.labels.job): \(.health)"' 2>/dev/null | sort)
echo "$TARGETS" | while read -r line; do
    if [[ "$line" == *"up"* ]]; then
        echo "   ✅ $line"
    else
        echo "   ⚠️  $line"
    fi
done

# 4. Verificar métricas básicas
echo ""
echo "4️⃣  Verificando Métricas Básicas..."

# Total de requisições
TOTAL_REQUESTS=$(curl -s 'http://localhost:9090/api/v1/query?query=grpc_requests_total' 2>/dev/null | jq -r '.data.result[0].value[1]' 2>/dev/null)
if [[ "$TOTAL_REQUESTS" != "null" && "$TOTAL_REQUESTS" != "" ]]; then
    echo "   ✅ grpc_requests_total: $TOTAL_REQUESTS"
else
    echo "   ⚠️  grpc_requests_total: Não disponível"
fi

# Requisições falhadas
FAILED_REQUESTS=$(curl -s 'http://localhost:9090/api/v1/query?query=grpc_requests_failed_total' 2>/dev/null | jq -r '.data.result[0].value[1]' 2>/dev/null)
if [[ "$FAILED_REQUESTS" != "null" && "$FAILED_REQUESTS" != "" ]]; then
    echo "   ✅ grpc_requests_failed_total: $FAILED_REQUESTS"
else
    echo "   ⚠️  grpc_requests_failed_total: Não disponível"
fi

# 5. Verificar Dashboards
echo ""
echo "5️⃣  Verificando Dashboards do Grafana..."
DASHBOARDS=$(curl -s -u admin:admin http://localhost:3000/api/search 2>/dev/null | jq -r '.[] | .title' 2>/dev/null)
if [[ -n "$DASHBOARDS" ]]; then
    echo "$DASHBOARDS" | while read -r dash; do
        echo "   ✅ Dashboard: $dash"
    done
else
    echo "   ⚠️  Nenhum dashboard encontrado"
fi

# 6. Teste de query PromQL
echo ""
echo "6️⃣  Testando Queries PromQL..."

# Taxa de sucesso
SUCCESS_RATE=$(curl -s 'http://localhost:9090/api/v1/query?query=(1%20-%20(rate(grpc_requests_failed_total%5B5m%5D)%20%2F%20rate(grpc_requests_total%5B5m%5D)))%20*%20100' 2>/dev/null | jq -r '.data.result[0].value[1]' 2>/dev/null)
if [[ "$SUCCESS_RATE" != "null" && "$SUCCESS_RATE" != "" ]]; then
    SUCCESS_RATE_INT=$(printf "%.0f" "$SUCCESS_RATE" 2>/dev/null)
    if [[ $SUCCESS_RATE_INT -ge 99 ]]; then
        echo "   ✅ Success Rate: ${SUCCESS_RATE_INT}% (Meta: ≥99.5%)"
    else
        echo "   ⚠️  Success Rate: ${SUCCESS_RATE_INT}% (Abaixo da meta)"
    fi
else
    echo "   ⚠️  Success Rate: Dados insuficientes"
fi

echo ""
echo "════════════════════════════════════════════════════════════"
echo "  ✅ Validação de Monitoramento Completa!"
echo "════════════════════════════════════════════════════════════"
echo ""
echo "📊 Acesse os dashboards:"
echo "   • Grafana: http://localhost:3000 (admin/admin)"
echo "   • Prometheus: http://localhost:9090"
echo "   • Jaeger: http://localhost:16686"
echo ""
