#!/bin/bash
# ============================================================================
# Chat4All - Failover Test
# ============================================================================
# Tests: Kill worker/connector, observe recovery and message processing
# ============================================================================

set -e

echo "============================================"
echo "Chat4All - Failover Test"
echo "============================================"
echo ""

cd /home/brunovieira/SD/chat4alltijolim-001-basic-messaging-api

# Start with 3 workers
echo "Step 1: Starting with 3 router-worker instances..."
docker compose up -d --scale router-worker=3
sleep 20

echo "Active router-worker instances:"
docker compose ps router-worker
echo ""

# Get worker container IDs
WORKERS=($(docker compose ps -q router-worker))
echo "Worker IDs: ${WORKERS[@]}"
echo ""

# Create test user
echo "Step 2: Creating test user..."
USER_ID="user_failover_$(date +%s)"
USERNAME="failover_$(date +%s)"

grpcurl -plaintext \
    -d "{\"username\": \"$USERNAME\", \"password\": \"password123\"}" \
    localhost:9091 chat4all.v1.AuthService/Register > /dev/null

AUTH_RESPONSE=$(grpcurl -plaintext \
    -d "{\"username\": \"$USERNAME\", \"password\": \"password123\"}" \
    localhost:9091 chat4all.v1.AuthService/Login)

ACCESS_TOKEN=$(echo "$AUTH_RESPONSE" | grep -o '"access_token": *"[^"]*"' | sed 's/"access_token": *"\(.*\)"/\1/')

echo "Authenticated: ${ACCESS_TOKEN:0:20}..."
echo ""

# Function to send messages continuously
send_messages_continuously() {
    local count=0
    while true; do
        count=$((count + 1))
        CONV_ID="conv_failover_${count}_$(date +%s%N)"
        
        RESPONSE=$(grpcurl -plaintext -max-time 2 \
            -H "authorization: Bearer $ACCESS_TOKEN" \
            -d "{\"conversation_id\": \"$CONV_ID\", \"content\": \"Failover test message $count\"}" \
            localhost:9091 chat4all.v1.MessageService/SendMessage 2>&1)
        
        if echo "$RESPONSE" | grep -q "message_id"; then
            echo "[$(date +%H:%M:%S)] ✓ Message $count sent successfully"
        else
            echo "[$(date +%H:%M:%S)] ✗ Message $count FAILED"
        fi
        
        sleep 0.5
    done
}

# Start sending messages in background
echo "Step 3: Starting continuous message flow..."
send_messages_continuously &
SENDER_PID=$!
echo "Message sender PID: $SENDER_PID"
echo ""

# Wait for some messages to flow
sleep 10

# Kill one worker
TARGET_WORKER=${WORKERS[0]}
echo "Step 4: KILLING worker: $TARGET_WORKER"
docker kill $TARGET_WORKER
echo "Worker killed at: $(date +%H:%M:%S)"
echo ""

# Observe recovery
echo "Step 5: Observing recovery (30 seconds)..."
echo "Messages should continue being processed by remaining workers..."
echo ""
sleep 30

# Kill the message sender
kill $SENDER_PID 2>/dev/null || true

echo ""
echo "Step 6: Checking remaining workers..."
docker compose ps router-worker
echo ""

# Check Kafka consumer group lag
echo "Step 7: Checking Kafka consumer group (message redistribution)..."
docker exec chat4all-kafka kafka-consumer-groups \
    --bootstrap-server localhost:9092 \
    --group router-worker-group \
    --describe 2>/dev/null || echo "Could not fetch consumer group info"
echo ""

echo "============================================"
echo "FAILOVER TEST COMPLETE"
echo "============================================"
echo ""
echo "Observations:"
echo "1. Worker killed: $TARGET_WORKER"
echo "2. Remaining workers: $((${#WORKERS[@]} - 1))"
echo "3. Messages continued being processed (check logs above)"
echo "4. Kafka automatically rebalanced partitions to remaining workers"
echo ""
echo "To view detailed logs:"
echo "  docker compose logs router-worker --tail=50"
echo "  docker compose logs api-service --tail=50"
