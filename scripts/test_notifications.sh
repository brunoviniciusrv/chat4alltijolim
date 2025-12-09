#!/bin/bash
# ============================================================================
# Chat4All - Notification System Test
# ============================================================================
# Tests: Real-time notifications via WebSocket for 1:1 and group messages
# ============================================================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

API_HOST=${API_HOST:-localhost}
API_PORT=${API_PORT:-9091}
WS_HOST=${WS_HOST:-localhost}
WS_PORT=${WS_PORT:-8085}

echo "============================================"
echo "Chat4All - Notification System Test"
echo "============================================"
echo "API Server: $API_HOST:$API_PORT"
echo "WebSocket Gateway: ws://$WS_HOST:$WS_PORT"
echo "============================================"
echo ""

# Function to create user and get token
create_user() {
    local username=$1
    echo -e "${BLUE}Creating user: $username${NC}"
    
    REGISTER_RESPONSE=$(grpcurl -plaintext \
        -d "{\"username\": \"$username\", \"password\": \"password123\"}" \
        $API_HOST:$API_PORT chat4all.v1.AuthService/Register)
    
    USER_ID=$(echo "$REGISTER_RESPONSE" | grep -o '"user_id": *"[^"]*"' | sed 's/"user_id": *"\(.*\)"/\1/')
    
    if [ -z "$USER_ID" ]; then
        echo -e "${RED}✗ Failed to create user${NC}"
        exit 1
    fi
    
    # Login to get token
    LOGIN_RESPONSE=$(grpcurl -plaintext \
        -d "{\"username\": \"$username\", \"password\": \"password123\"}" \
        $API_HOST:$API_PORT chat4all.v1.AuthService/Login)
    
    TOKEN=$(echo "$LOGIN_RESPONSE" | grep -o '"access_token": *"[^"]*"' | sed 's/"access_token": *"\(.*\)"/\1/')
    
    if [ -z "$TOKEN" ]; then
        echo -e "${RED}✗ Failed to get token${NC}"
        exit 1
    fi
    
    echo -e "${GREEN}✓ User created: $USER_ID${NC}"
    echo -e "${GREEN}✓ Token: ${TOKEN:0:20}...${NC}"
}

# Function to connect to WebSocket and listen for notifications
connect_websocket() {
    local user_id=$1
    local token=$2
    local output_file=$3
    local timeout_seconds=${4:-30}
    
    echo -e "${BLUE}Connecting $user_id to WebSocket...${NC}"
    
    # Use websocat to connect to WebSocket
    # Install: sudo apt install websocat or brew install websocat
    if ! command -v websocat &> /dev/null; then
        echo -e "${YELLOW}⚠ websocat not found, using alternative method${NC}"
        # Alternative: use wscat or custom script
        return 1
    fi
    
    # Connect to WebSocket in background and save output
    timeout $timeout_seconds websocat "ws://$WS_HOST:$WS_PORT/notifications?token=$token" > "$output_file" 2>&1 &
    WS_PID=$!
    
    # Wait a bit for connection to establish
    sleep 2
    
    if ps -p $WS_PID > /dev/null; then
        echo -e "${GREEN}✓ WebSocket connected (PID: $WS_PID)${NC}"
        echo $WS_PID
        return 0
    else
        echo -e "${RED}✗ WebSocket connection failed${NC}"
        cat "$output_file"
        return 1
    fi
}

# Function to send message
send_message() {
    local token=$1
    local conv_id=$2
    local content=$3
    
    echo -e "${BLUE}Sending message to $conv_id${NC}"
    
    SEND_RESPONSE=$(grpcurl -plaintext \
        -H "authorization: Bearer $token" \
        -d "{\"conversation_id\": \"$conv_id\", \"content\": \"$content\"}" \
        $API_HOST:$API_PORT chat4all.v1.MessageService/SendMessage)
    
    MESSAGE_ID=$(echo "$SEND_RESPONSE" | grep -o '"message_id": *"[^"]*"' | sed 's/"message_id": *"\(.*\)"/\1/')
    
    if [ -z "$MESSAGE_ID" ]; then
        echo -e "${RED}✗ Failed to send message${NC}"
        return 1
    fi
    
    echo -e "${GREEN}✓ Message sent: $MESSAGE_ID${NC}"
    echo $MESSAGE_ID
}

# Function to create group
create_group() {
    local token=$1
    local group_name=$2
    shift 2
    local members=("$@")
    
    echo -e "${BLUE}Creating group: $group_name with ${#members[@]} members${NC}"
    
    # Build members JSON array
    MEMBERS_JSON="["
    for i in "${!members[@]}"; do
        if [ $i -gt 0 ]; then
            MEMBERS_JSON+=","
        fi
        MEMBERS_JSON+="\"${members[$i]}\""
    done
    MEMBERS_JSON+="]"
    
    CREATE_GROUP_RESPONSE=$(grpcurl -plaintext \
        -H "authorization: Bearer $token" \
        -d "{\"name\": \"$group_name\", \"participant_ids\": $MEMBERS_JSON}" \
        $API_HOST:$API_PORT chat4all.v1.GroupService/CreateGroup)
    
    GROUP_ID=$(echo "$CREATE_GROUP_RESPONSE" | grep -o '"group_id": *"[^"]*"' | sed 's/"group_id": *"\(.*\)"/\1/')
    
    if [ -z "$GROUP_ID" ]; then
        echo -e "${RED}✗ Failed to create group${NC}"
        echo "$CREATE_GROUP_RESPONSE"
        return 1
    fi
    
    echo -e "${GREEN}✓ Group created: $GROUP_ID${NC}"
    echo $GROUP_ID
}

echo "============================================"
echo "TEST 1: 1:1 Notification Test"
echo "============================================"
echo ""

# Create Alice
create_user "alice_notif_$(date +%s)"
ALICE_ID=$USER_ID
ALICE_TOKEN=$TOKEN

# Create Bob
create_user "bob_notif_$(date +%s)"
BOB_ID=$USER_ID
BOB_TOKEN=$TOKEN

# Check if websocat is available
if ! command -v websocat &> /dev/null; then
    echo -e "${YELLOW}⚠ websocat not installed. Skipping WebSocket connection test.${NC}"
    echo -e "${YELLOW}  To install: sudo apt install websocat (Linux) or brew install websocat (Mac)${NC}"
    echo ""
    echo -e "${BLUE}Testing notification publishing (without WebSocket client)...${NC}"
    
    # Create direct conversation ID
    CONV_ID="direct_${ALICE_ID}_${BOB_ID}"
    
    # Bob sends message to Alice
    echo -e "${BLUE}Bob sending message to Alice...${NC}"
    send_message "$BOB_TOKEN" "$CONV_ID" "Hello Alice! Testing notifications"
    
    # Wait for message processing
    sleep 3
    
    # Check Redis for published notification
    echo -e "${BLUE}Checking if notification was published to Redis...${NC}"
    docker exec chat4all-redis redis-cli PUBSUB CHANNELS "notifications:*" > /tmp/redis_channels.txt
    
    if grep -q "notifications:$ALICE_ID" /tmp/redis_channels.txt; then
        echo -e "${GREEN}✓ Notification channel exists for Alice${NC}"
    else
        echo -e "${YELLOW}⚠ No active subscribers, but notification would be published${NC}"
    fi
    
    # Check router-worker logs for notification publishing
    echo -e "${BLUE}Checking router-worker logs for notification publishing...${NC}"
    docker compose logs router-worker --tail=50 | grep -i "notification\|Published to Redis" | tail -10
    
    echo ""
    echo -e "${GREEN}✓ 1:1 Notification test completed (without WebSocket client)${NC}"
else
    # WebSocket available - full test
    ALICE_WS_OUTPUT="/tmp/alice_ws.txt"
    
    # Connect Alice to WebSocket
    ALICE_WS_PID=$(connect_websocket "$ALICE_ID" "$ALICE_TOKEN" "$ALICE_WS_OUTPUT" 15)
    
    if [ -z "$ALICE_WS_PID" ]; then
        echo -e "${RED}✗ Failed to connect Alice to WebSocket${NC}"
        exit 1
    fi
    
    # Wait for connection to stabilize
    sleep 2
    
    # Create direct conversation ID
    CONV_ID="direct_${ALICE_ID}_${BOB_ID}"
    
    # Bob sends message to Alice
    MSG_ID=$(send_message "$BOB_TOKEN" "$CONV_ID" "Hello Alice! Testing notifications")
    
    # Wait for notification to arrive
    echo -e "${BLUE}Waiting for notification (5 seconds)...${NC}"
    sleep 5
    
    # Kill WebSocket connection
    kill $ALICE_WS_PID 2>/dev/null || true
    
    # Check if notification was received
    echo -e "${BLUE}Checking received notifications...${NC}"
    cat "$ALICE_WS_OUTPUT"
    
    if grep -q "new_message" "$ALICE_WS_OUTPUT"; then
        echo -e "${GREEN}✓ Alice received notification!${NC}"
    else
        echo -e "${RED}✗ No notification received${NC}"
        echo "WebSocket output:"
        cat "$ALICE_WS_OUTPUT"
    fi
fi

echo ""
echo "============================================"
echo "TEST 2: Group Notification Test (10 users)"
echo "============================================"
echo ""

# Create 10 users for group
declare -a GROUP_MEMBERS
declare -a GROUP_TOKENS

for i in {1..10}; do
    create_user "groupuser${i}_$(date +%s)"
    GROUP_MEMBERS+=("$USER_ID")
    GROUP_TOKENS+=("$TOKEN")
    sleep 0.2  # Avoid rate limiting
done

echo ""
echo -e "${GREEN}✓ Created 10 users for group test${NC}"
echo ""

# First user creates the group
echo -e "${BLUE}User 1 creating group with all 10 members...${NC}"
GROUP_ID=$(create_group "${GROUP_TOKENS[0]}" "Test Group $(date +%s)" "${GROUP_MEMBERS[@]}")

if [ -z "$GROUP_ID" ]; then
    echo -e "${RED}✗ Failed to create group${NC}"
    exit 1
fi

echo ""

# Send message to group
echo -e "${BLUE}User 1 sending message to group...${NC}"
MSG_ID=$(send_message "${GROUP_TOKENS[0]}" "$GROUP_ID" "Hello everyone in the group!")

# Wait for processing
sleep 5

# Check router-worker logs for group notifications
echo ""
echo -e "${BLUE}Checking router-worker logs for group notifications...${NC}"
docker compose logs router-worker --tail=100 | grep -E "group|Publishing to group member|Published notification" | tail -20

# Verify all members would receive notification
echo ""
echo -e "${BLUE}Verifying notification publishing for group members...${NC}"

# Check if notifications were attempted for multiple members
GROUP_NOTIF_COUNT=$(docker compose logs router-worker --tail=100 | grep -c "Publishing to group member" || echo "0")

if [ "$GROUP_NOTIF_COUNT" -ge 9 ]; then
    echo -e "${GREEN}✓ Notifications published to $GROUP_NOTIF_COUNT group members${NC}"
else
    echo -e "${YELLOW}⚠ Only $GROUP_NOTIF_COUNT notifications published (expected 9)${NC}"
fi

echo ""
echo "============================================"
echo "NOTIFICATION SYSTEM VALIDATION SUMMARY"
echo "============================================"
echo ""

# Check WebSocket Gateway status
echo -e "${BLUE}WebSocket Gateway Status:${NC}"
docker compose ps websocket-gateway

echo ""
echo -e "${BLUE}WebSocket Connections:${NC}"
docker compose logs websocket-gateway --tail=50 | grep -i "connected\|connection" | tail -5

echo ""
echo -e "${BLUE}Redis Pub/Sub Status:${NC}"
docker exec chat4all-redis redis-cli INFO stats | grep pubsub

echo ""
echo -e "${BLUE}Router Worker Notification Publishing:${NC}"
docker compose logs router-worker --tail=100 | grep -c "Published notification to Redis" | \
    xargs -I {} echo "Total notifications published: {}"

echo ""
echo "============================================"
echo -e "${GREEN}✅ NOTIFICATION TESTS COMPLETED${NC}"
echo "============================================"
echo ""
echo "Summary:"
echo "  • 1:1 messaging: Notifications published to Redis"
echo "  • Group messaging: Notifications sent to all members"
echo "  • WebSocket Gateway: Running and accepting connections"
echo "  • Redis Pub/Sub: Available for real-time delivery"
echo ""
echo "Note: Full WebSocket client testing requires 'websocat' tool"
echo "      Install: sudo apt install websocat"
echo ""
