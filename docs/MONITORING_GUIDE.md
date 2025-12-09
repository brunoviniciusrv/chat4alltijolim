# Chat4All - Guia de Monitoramento

## 🎯 Acesso Rápido

### Prometheus
- **URL:** http://localhost:9090
- **Função:** Coleta e armazena métricas de todos os serviços
- **Principais queries:**
  - Total de requisições: `grpc_requests_total`
  - Taxa de sucesso: `(1 - (rate(grpc_requests_failed_total[5m]) / rate(grpc_requests_total[5m]))) * 100`
  - Latência P99: `histogram_quantile(0.99, rate(grpc_request_duration_seconds_bucket[5m]))`

### Grafana
- **URL:** http://localhost:3000
- **Credenciais:** admin / admin
- **Dashboards disponíveis:**
  - Chat4All - System Overview (visão geral do sistema)

---

## 📊 Dashboards Grafana

### 1. System Overview
**Arquivo:** `monitoring/grafana/dashboards/1-overview.json`

**Painéis incluídos:**

#### Status dos Serviços
- **API Service Status**: Mostra se API está UP/DOWN
- **Router Worker Status**: Mostra se Worker está UP/DOWN
- Cores: 🟢 Verde (UP) | 🔴 Vermelho (DOWN)

#### Métricas de Desempenho
- **Success Rate (5m)**: Taxa de sucesso nas últimas 5 minutos
  - 🟢 Verde: ≥ 99.5%
  - 🟡 Amarelo: 95-99.5%
  - 🔴 Vermelho: < 95%

- **P99 Latency**: Latência no percentil 99
  - 🟢 Verde: < 100ms
  - 🟡 Amarelo: 100-200ms
  - 🔴 Vermelho: > 200ms
  - **Target:** < 200ms (RNF-006)

- **Total Requests**: Contador total de requisições processadas

#### Gráficos Temporais

**gRPC Request Rate:**
- Total Requests/s (linha azul)
- Failed Requests/s (linha vermelha)
- Mostra carga em tempo real
- Atualiza a cada 5 segundos

**gRPC Latency Percentiles:**
- P50 (mediana) - verde
- P95 - amarelo
- P99 (target < 200ms) - vermelho
- Linha vermelha horizontal em 0.2s indica threshold

**Estatísticas exibidas:**
- Mean (média)
- Last (último valor)
- Max (valor máximo)

---

## 🔍 Prometheus - Métricas Disponíveis

### API Service (porta 8080)

#### Métricas de Requisições gRPC
```promql
# Total de requisições
grpc_requests_total

# Requisições falhas
grpc_requests_failed_total

# Taxa de erro (percentual)
(rate(grpc_requests_failed_total[1m]) / rate(grpc_requests_total[1m])) * 100

# Taxa de sucesso (percentual)
(1 - (rate(grpc_requests_failed_total[5m]) / rate(grpc_requests_total[5m]))) * 100
```

#### Métricas de Latência
```promql
# Latência P50 (mediana)
histogram_quantile(0.50, rate(grpc_request_duration_seconds_bucket[5m]))

# Latência P95
histogram_quantile(0.95, rate(grpc_request_duration_seconds_bucket[5m]))

# Latência P99 (SLA < 200ms)
histogram_quantile(0.99, rate(grpc_request_duration_seconds_bucket[5m]))
```

#### Métricas de Mensagens
```promql
# Total de mensagens enviadas
messages_sent_total

# Taxa de envio (msg/s)
rate(messages_sent_total[1m])

# Total de mensagens entregues
messages_delivered_total

# Taxa de entrega (msg/s)
rate(messages_delivered_total[1m])
```

#### Métricas de Upload/Download de Arquivos
```promql
# Total de uploads
file_upload_total

# Total de downloads
file_download_total

# Tamanho dos arquivos (bytes)
file_upload_size_bytes
```

### Router Worker (porta 8082)

```promql
# Mensagens consumidas do Kafka
messages_consumed_total{topic="messages"}

# Mensagens processadas com sucesso
messages_processed_total{status="success"}

# Mensagens falhadas
messages_failed_total

# Tempo de processamento
processing_duration_seconds

# Tempo de escrita no Cassandra
cassandra_write_duration_seconds
```

### WebSocket Gateway (porta 9095)

```promql
# Conexões ativas
websocket_connections_active

# Notificações enviadas
websocket_notifications_sent_total

# Taxa de notificações
rate(websocket_notifications_sent_total[1m])
```

---

## 📈 Queries Úteis para Monitoramento

### SLA e Disponibilidade

#### Uptime dos Serviços
```promql
# Verifica quais serviços estão UP
up{job=~"api-service|router-worker|websocket-gateway"}

# Tempo UP nas últimas 24h (percentual)
avg_over_time(up{job="api-service"}[24h]) * 100
```

#### Taxa de Sucesso (SLA ≥ 99.95%)
```promql
# Taxa de sucesso nas últimas 5 minutos
(1 - (rate(grpc_requests_failed_total[5m]) / rate(grpc_requests_total[5m]))) * 100

# Taxa de sucesso nas últimas 24 horas
(1 - (rate(grpc_requests_failed_total[24h]) / rate(grpc_requests_total[24h]))) * 100
```

### Performance

#### Throughput (Requisições por Segundo)
```promql
# Requisições/segundo nas últimas 1 min
rate(grpc_requests_total[1m])

# Requisições/segundo nas últimas 5 min
rate(grpc_requests_total[5m])

# Pico de requisições (máximo em 1h)
max_over_time(rate(grpc_requests_total[1m])[1h:1m])
```

#### Latência por Percentil
```promql
# P50, P95, P99 em uma query
histogram_quantile(0.50, rate(grpc_request_duration_seconds_bucket[5m]))
histogram_quantile(0.95, rate(grpc_request_duration_seconds_bucket[5m]))
histogram_quantile(0.99, rate(grpc_request_duration_seconds_bucket[5m]))
```

### Análise de Erros

#### Taxa de Erro
```promql
# Erros por segundo
rate(grpc_requests_failed_total[1m])

# Percentual de erro
(rate(grpc_requests_failed_total[1m]) / rate(grpc_requests_total[1m])) * 100
```

#### Identificar Picos de Erro
```promql
# Pico de erros na última hora
max_over_time(rate(grpc_requests_failed_total[1m])[1h:1m])

# Delta de erros (variação)
delta(grpc_requests_failed_total[5m])
```

---

## 🚨 Alertas Prometheus

### Alertas Configurados

Os alertas estão definidos em `monitoring/prometheus-alerts.yml`:

#### Alertas Críticos (SLA Impact)

**APIServiceDown:**
- Condição: `up{job="api-service"} == 0`
- Duração: > 1 minuto
- Severidade: CRITICAL
- Impacto: Usuários não podem enviar/receber mensagens

**RouterWorkerDown:**
- Condição: `up{job="router-worker"} == 0`
- Duração: > 2 minutos
- Severidade: CRITICAL
- Impacto: Mensagens não sendo processadas

**HighLatencyP99:**
- Condição: P99 > 200ms
- Duração: > 5 minutos
- Severidade: WARNING
- Impacto: Usuários com respostas lentas

**HighErrorRate:**
- Condição: Taxa de erro > 1%
- Duração: > 2 minutos
- Severidade: WARNING
- Impacto: Falhas em requisições

---

## 🔧 Configuração

### Prometheus

**Arquivo:** `monitoring/prometheus.yml`

**Intervalos de Scrape:**
- API Service: 5s
- Router Worker: 5s
- WebSocket Gateway: 5s
- Prometheus (self): 15s

**Retenção de Dados:** Padrão (15 dias)

**Recarregar configuração sem reiniciar:**
```bash
curl -X POST http://localhost:9090/-/reload
```

### Grafana

**Provisioning Automático:**
- Datasource: `monitoring/grafana/provisioning/datasources/prometheus.yml`
- Dashboards: `monitoring/grafana/provisioning/dashboards/dashboards.yml`

**Dashboards:**
- Path: `monitoring/grafana/dashboards/`
- Auto-load: Sim (a cada 10 segundos)

**Criar novo dashboard:**
1. Copiar dashboard existente em `monitoring/grafana/dashboards/`
2. Modificar JSON
3. Aguardar 10 segundos ou reiniciar Grafana

---

## 📋 Checklist de Validação

### ✅ Verificar Monitoramento Funcionando

**Prometheus:**
```bash
# Verificar status
curl -s http://localhost:9090/-/healthy

# Ver targets (todos devem estar "up")
curl -s http://localhost:9090/api/v1/targets | jq -r '.data.activeTargets[] | "\(.labels.job): \(.health)"'

# Executar query de teste
curl -s 'http://localhost:9090/api/v1/query?query=up' | jq '.data.result'
```

**Grafana:**
```bash
# Verificar status
curl -s http://localhost:3000/api/health

# Listar datasources
curl -s -u admin:admin http://localhost:3000/api/datasources | jq '.[].name'

# Listar dashboards
curl -s -u admin:admin http://localhost:3000/api/search | jq '.[] | {title, uid}'
```

### ✅ Métricas de Validação

Após enviar mensagens de teste, verificar:

1. **Total de requisições aumentando:**
   ```promql
   grpc_requests_total
   ```

2. **Sem falhas:**
   ```promql
   grpc_requests_failed_total
   ```

3. **Latência dentro do target (< 200ms):**
   ```promql
   histogram_quantile(0.99, rate(grpc_request_duration_seconds_bucket[5m]))
   ```

4. **Taxa de sucesso ≥ 99.5%:**
   ```promql
   (1 - (rate(grpc_requests_failed_total[5m]) / rate(grpc_requests_total[5m]))) * 100
   ```

---

## 🎯 Casos de Uso

### Validar Performance do Sistema

**Cenário:** Enviar 100 mensagens e verificar latência

```bash
# 1. Executar teste de carga
./load-tests/simple-throughput-test.sh

# 2. Abrir Grafana: http://localhost:3000
# 3. Dashboard: Chat4All - System Overview
# 4. Verificar gráfico "gRPC Latency Percentiles"
# 5. P99 deve estar < 200ms
```

### Investigar Erros

**Cenário:** Taxa de erro aumentou

```bash
# 1. Verificar quantos erros
curl -s 'http://localhost:9090/api/v1/query?query=grpc_requests_failed_total' | jq '.data.result[0].value[1]'

# 2. Ver taxa de erro
curl -s 'http://localhost:9090/api/v1/query?query=(rate(grpc_requests_failed_total[5m]) / rate(grpc_requests_total[5m])) * 100' | jq '.data.result[0].value[1]'

# 3. Ver logs do serviço
docker compose logs -f api-service | grep ERROR
```

### Monitorar Upload de Arquivos

**Cenário:** Validar uploads grandes (1GB)

```bash
# 1. Executar teste de upload
python3 test_file_upload.py

# 2. Em outra janela, monitorar métricas
watch -n 1 'curl -s http://localhost:8080/metrics | grep file_upload'

# 3. Ver histórico no Grafana
# Query: rate(file_upload_total[1m])
```

---

## 🚀 Próximas Melhorias

### Dashboards Adicionais

1. **Infrastructure Health:**
   - CPU/Memória dos containers
   - Uso de disco
   - Network I/O

2. **Kafka Performance:**
   - Consumer lag
   - Messages in/out
   - Partition distribution

3. **Cassandra Metrics:**
   - Read/Write latency
   - Query performance
   - Storage usage

4. **Business Metrics:**
   - Mensagens por usuário
   - Arquivos por tamanho
   - Distribuição de canais (WhatsApp, Instagram)

### Alertas Adicionais

- Low throughput (< 10 msg/s quando esperado > 50)
- Kafka consumer lag > 1000
- File upload failures
- WebSocket disconnections > 10%

---

## 📚 Referências

- [Prometheus Documentation](https://prometheus.io/docs/)
- [Grafana Documentation](https://grafana.com/docs/)
- [PromQL Cheat Sheet](https://promlabs.com/promql-cheat-sheet/)
- [Grafana Dashboard Best Practices](https://grafana.com/docs/grafana/latest/dashboards/build-dashboards/best-practices/)

---

**Última atualização:** 07/12/2025  
**Versão:** 1.0  
**Status:** ✅ Prometheus e Grafana configurados e funcionando
