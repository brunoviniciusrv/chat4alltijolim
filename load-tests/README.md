# Chat4All Load Tests

Testes de carga, escalabilidade e failover para a plataforma Chat4All.

## Scripts Disponíveis

### 1. Simple Throughput Test
```bash
./simple-throughput-test.sh
```
- Envia mensagens em paralelo usando grpcurl
- Mede throughput (msg/s) e latência média
- Configurável via variáveis de ambiente:
  - `NUM_MESSAGES=1000` - Total de mensagens
  - `CONCURRENCY=10` - Requisições paralelas

### 2. Scalability Test
```bash
./scalability-test.sh
```
- Escala router-worker horizontalmente (1, 2, 3, 5 instâncias)
- Mede aumento de throughput com mais nós
- Gera arquivo CSV com resultados
- **Demonstra**: Escalabilidade horizontal via Kafka partitioning

### 3. Failover Test
```bash
./failover-test.sh
```
- Inicia 3 workers
- Envia mensagens continuamente
- Mata 1 worker durante execução
- **Demonstra**: Kafka rebalanceamento automático + tolerância a falhas

### 4. Large File Test
```bash
./large-file-test.sh
```
- Testa upload de arquivo grande (1GB padrão)
- Valida estabilidade do sistema
- **Demonstra**: Multipart upload + MinIO backend

### 5. K6 Load Test (Avançado)
```bash
k6 run k6-load-test.js
```
- Simula 100+ usuários concorrentes
- Gera métricas detalhadas (P95, P99, error rate)
- Requer instalação do k6: https://k6.io/docs/getting-started/installation/

## Métricas Coletadas

Todos os testes coletam:
- **Throughput**: mensagens/segundo
- **Latência**: média, P95, P99
- **Taxa de erro**: falhas / total
- **Duração**: tempo total do teste

## Resultados Esperados

### Scalability Test
```
workers | messages | duration | throughput
--------|----------|----------|------------
1       | 500      | 45s      | 11 msg/s
2       | 500      | 25s      | 20 msg/s
3       | 500      | 18s      | 27 msg/s
5       | 500      | 12s      | 41 msg/s
```

### Failover Test
- ✅ Mensagens continuam sendo processadas após falha de worker
- ✅ Kafka rebalanceia partições automaticamente
- ✅ Nenhuma mensagem perdida (at-least-once delivery)

## Monitoramento em Tempo Real

### Prometheus Metrics
```bash
# Acessar métricas do API Service
curl http://localhost:8080/metrics

# Acessar métricas do Router Worker
curl http://localhost:8081/metrics
```

### Grafana Dashboards
```
URL: http://localhost:3000
User: admin
Pass: admin

Dashboards disponíveis:
- API Service Overview
- Router Worker Metrics
- Connector Metrics
- System Overview
```

### Kafka Consumer Lag
```bash
docker exec chat4all-kafka kafka-consumer-groups \
    --bootstrap-server localhost:9092 \
    --group router-worker-group \
    --describe
```

## Requisitos

- Docker e Docker Compose
- grpcurl instalado
- (Opcional) k6 para testes avançados
- (Opcional) GNU parallel para throughput test

## Instalação de Dependências

```bash
# grpcurl (Ubuntu/Debian)
sudo apt-get install grpcurl

# k6 (Ubuntu/Debian)
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update
sudo apt-get install k6

# GNU parallel (opcional)
sudo apt-get install parallel
```
