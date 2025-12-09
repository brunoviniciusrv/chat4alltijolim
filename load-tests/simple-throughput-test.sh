#!/bin/bash
# ============================================================================
# Chat4All - Simple Throughput Test
# ============================================================================
# Tests message throughput without k6 (using grpcurl in parallel)
# ============================================================================

set -e

API_HOST=${API_HOST:-localhost}
API_PORT=${API_PORT:-9091}
NUM_MESSAGES=${NUM_MESSAGES:-1000}
CONCURRENCY=${CONCURRENCY:-10}

echo "============================================"
echo "Chat4All - Throughput Test"
echo "============================================"
echo "Target: $API_HOST:$API_PORT"
echo "Messages: $NUM_MESSAGES"
echo "Concurrency: $CONCURRENCY"
echo "============================================"
echo ""

# Create test user
USER_ID="user_throughput_$(date +%s)"
USERNAME="throughput_$(date +%s)"

echo "Creating test user..."
grpcurl -plaintext \
    -d "{\"username\": \"$USERNAME\", \"password\": \"password123\"}" \
    $API_HOST:$API_PORT chat4all.v1.AuthService/Register > /dev/null

# Login and get token
echo "Authenticating..."
AUTH_RESPONSE=$(grpcurl -plaintext \
    -d "{\"username\": \"$USERNAME\", \"password\": \"password123\"}" \
    $API_HOST:$API_PORT chat4all.v1.AuthService/Login)

ACCESS_TOKEN=$(echo "$AUTH_RESPONSE" | grep -o '"access_token": *"[^"]*"' | sed 's/"access_token": *"\(.*\)"/\1/')

if [ -z "$ACCESS_TOKEN" ]; then
    echo "Failed to get access token"
    exit 1
fi

echo "Token: ${ACCESS_TOKEN:0:20}..."
echo ""

# Function to send single message
send_message() {
    local msg_num=$1
    local conv_id="conv_throughput_$(date +%s%N)"
    
    grpcurl -plaintext \
        -H "authorization: Bearer $ACCESS_TOKEN" \
        -d "{\"conversation_id\": \"$conv_id\", \"content\": \"Throughput test message $msg_num\"}" \
        $API_HOST:$API_PORT chat4all.v1.MessageService/SendMessage > /dev/null 2>&1
    
    if [ $? -eq 0 ]; then
        echo -n "."
    else
        echo -n "E"
    fi
}

export -f send_message
export ACCESS_TOKEN API_HOST API_PORT

echo "Sending $NUM_MESSAGES messages with concurrency $CONCURRENCY..."
echo ""

START_TIME=$(date +%s)

# Use GNU parallel if available, otherwise xargs
if command -v parallel > /dev/null 2>&1; then
    seq 1 $NUM_MESSAGES | parallel -j $CONCURRENCY send_message {}
else
    seq 1 $NUM_MESSAGES | xargs -P $CONCURRENCY -I {} bash -c "send_message {}"
fi

END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))

echo ""
echo ""
echo "============================================"
echo "RESULTS"
echo "============================================"
echo "Total messages: $NUM_MESSAGES"
echo "Duration: ${DURATION}s"
echo "Throughput: $((NUM_MESSAGES / DURATION)) msg/s"
echo "Average latency: ~$((DURATION * 1000 / NUM_MESSAGES)) ms/msg"
echo "============================================"
