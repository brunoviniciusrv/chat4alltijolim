#!/bin/bash
# ============================================================================
# Chat4All - Scalability Test (Horizontal Scaling)
# ============================================================================
# Tests: Scale router-worker and connectors, measure throughput increase
# ============================================================================

set -e

echo "============================================"
echo "Chat4All - Scalability Test"
echo "============================================"
echo ""

# Test configurations
declare -a WORKER_COUNTS=(1 2 3 5)
NUM_MESSAGES=500
CONCURRENCY=20

RESULTS_FILE="scalability-results-$(date +%Y%m%d_%H%M%S).csv"
echo "workers,messages,duration_sec,throughput_msg_per_sec" > $RESULTS_FILE

cd /home/brunovieira/SD/chat4alltijolim-001-basic-messaging-api

for WORKERS in "${WORKER_COUNTS[@]}"; do
    echo "============================================"
    echo "Testing with $WORKERS router-worker instances"
    echo "============================================"
    
    # Scale router-worker
    echo "Scaling router-worker to $WORKERS instances..."
    docker compose up -d --scale router-worker=$WORKERS
    
    # Wait for workers to be ready
    echo "Waiting 20s for workers to stabilize..."
    sleep 20
    
    # Check how many workers are running
    RUNNING_WORKERS=$(docker compose ps router-worker | grep -c "Up" || echo "0")
    echo "Running workers: $RUNNING_WORKERS"
    
    # Run throughput test
    echo "Running throughput test..."
    START_TIME=$(date +%s)
    
    # Create test user
    USER_ID="user_scale_${WORKERS}_$(date +%s)"
    USERNAME="scale${WORKERS}_$(date +%s)"
    
    grpcurl -plaintext \
        -d "{\"username\": \"$USERNAME\", \"password\": \"password123\"}" \
        localhost:9091 chat4all.v1.AuthService/Register > /dev/null
    
    AUTH_RESPONSE=$(grpcurl -plaintext \
        -d "{\"username\": \"$USERNAME\", \"password\": \"password123\"}" \
        localhost:9091 chat4all.v1.AuthService/Login)
    
    ACCESS_TOKEN=$(echo "$AUTH_RESPONSE" | grep -o '"access_token": *"[^"]*"' | sed 's/"access_token": *"\(.*\)"/\1/')
    
    # Send messages
    SUCCESS_COUNT=0
    for i in $(seq 1 $NUM_MESSAGES); do
        CONV_ID="conv_scale_${WORKERS}_${i}_$(date +%s%N)"
        
        RESPONSE=$(grpcurl -plaintext \
            -H "authorization: Bearer $ACCESS_TOKEN" \
            -d "{\"conversation_id\": \"$CONV_ID\", \"content\": \"Scale test $i\"}" \
            localhost:9091 chat4all.v1.MessageService/SendMessage 2>&1)
        
        if echo "$RESPONSE" | grep -q "message_id"; then
            SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
            echo -n "."
        else
            echo -n "E"
        fi
        
        # Small delay to avoid overwhelming
        sleep 0.01
    done
    
    END_TIME=$(date +%s)
    DURATION=$((END_TIME - START_TIME))
    THROUGHPUT=$((SUCCESS_COUNT / DURATION))
    
    echo ""
    echo "Results: $SUCCESS_COUNT messages in ${DURATION}s = $THROUGHPUT msg/s"
    echo "$WORKERS,$SUCCESS_COUNT,$DURATION,$THROUGHPUT" >> $RESULTS_FILE
    echo ""
    
    # Wait before next test
    sleep 5
done

echo ""
echo "============================================"
echo "SCALABILITY TEST COMPLETE"
echo "============================================"
echo "Results saved to: $RESULTS_FILE"
echo ""
cat $RESULTS_FILE
echo ""
echo "Visualization:"
column -t -s',' $RESULTS_FILE
