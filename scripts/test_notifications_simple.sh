#!/bin/bash
# ============================================================================
# Chat4All - Simplified Notification Validation Test
# ============================================================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

API_HOST=${API_HOST:-localhost}
API_PORT=${API_PORT:-9091}

echo "============================================"
echo "Chat4All - Notification Validation"
echo "============================================"
echo ""

# Function to create user
create_user() {
    local username=$1
    echo -e "${BLUE}Creating $username...${NC}"
    
    RESPONSE=$(grpcurl -plaintext \
        -d "{\"username\": \"$username\", \"password\": \"password123\"}" \
        $API_HOST:$API_PORT chat4all.v1.AuthService/Register 2>&1)
    
    USER_ID=$(echo "$RESPONSE" | grep -o '"user_id": *"[^"]*"' | sed 's/"user_id": *"\(.*\)"/\1/')
    
    LOGIN=$(grpcurl -plaintext \
        -d "{\"username\": \"$username\", \"password\": \"password123\"}" \
        $API_HOST:$API_PORT chat4all.v1.AuthService/Login 2>&1)
    
    TOKEN=$(echo "$LOGIN" | grep -o '"access_token": *"[^"]*"' | sed 's/"access_token": *"\(.*\)"/\1/')
    
    echo -e "${GREEN}✓ Created: $USER_ID${NC}"
}

echo "============================================"
echo "TEST 1: 1:1 Message Notification"
echo "============================================"
echo ""

# Create Alice
create_user "alice_val_$(date +%s)"
ALICE_ID=$USER_ID
ALICE_TOKEN=$TOKEN
sleep 1

# Create Bob
create_user "bob_val_$(date +%s)"
BOB_ID=$USER_ID
BOB_TOKEN=$TOKEN

echo ""
echo -e "${BLUE}Bob sends message to Alice...${NC}"
CONV_ID="direct_${ALICE_ID}_${BOB_ID}"

grpcurl -plaintext \
    -H "authorization: Bearer $BOB_TOKEN" \
    -d "{\"conversation_id\": \"$CONV_ID\", \"content\": \"Test notification\"}" \
    $API_HOST:$API_PORT chat4all.v1.MessageService/SendMessage > /dev/null 2>&1

echo -e "${GREEN}✓ Message sent${NC}"
echo ""
echo -e "${BLUE}Waiting for processing (3s)...${NC}"
sleep 3

echo ""
echo "============================================"
echo "Verification: 1:1 Notifications"
echo "============================================"

# Check router-worker logs for notification
echo ""
echo -e "${BLUE}Router Worker Logs (last 30 lines):${NC}"
docker compose logs router-worker --tail=30 2>&1 | grep -A2 -B2 "notification\|Published to Redis"

echo ""
echo "Check 1: Was notification published to Redis?"
NOTIF_COUNT=$(docker compose logs router-worker --tail=30 2>&1 | grep -c "Published notification to Redis for user: $ALICE_ID" || echo "0")

if [ "$NOTIF_COUNT" -gt 0 ]; then
    echo -e "${GREEN}✓ YES - Notification published to Alice ($NOTIF_COUNT times)${NC}"
else
    echo -e "${RED}✗ NO - Notification NOT published${NC}"
    echo ""
    echo "Debug info:"
    docker compose logs router-worker --tail=50 2>&1 | grep -E "conversation_id: $CONV_ID|recipient|Extracted"
fi

echo ""
echo "Check 2: WebSocket Gateway status"
docker compose ps websocket-gateway

echo ""
echo "Check 3: Redis subscriber active?"
docker compose logs websocket-gateway --tail=20 2>&1 | grep -i "subscriber\|redis"

echo ""
echo "============================================"
echo "TEST 2: Group Message Notification (10 users)"
echo "============================================"
echo ""

# Create 10 users
declare -a USERS
declare -a TOKENS

for i in {1..10}; do
    create_user "grp${i}_$(date +%s)"
    USERS+=("$USER_ID")
    TOKENS+=("$TOKEN")
    sleep 0.3
done

echo ""
echo -e "${GREEN}✓ Created 10 users${NC}"
echo ""

# Create group
echo -e "${BLUE}Creating group...${NC}"

MEMBERS_JSON="["
for i in "${!USERS[@]}"; do
    if [ $i -gt 0 ]; then
        MEMBERS_JSON+=","
    fi
    MEMBERS_JSON+="\"${USERS[$i]}\""
done
MEMBERS_JSON+="]"

GROUP_RESPONSE=$(grpcurl -plaintext \
    -H "authorization: Bearer ${TOKENS[0]}" \
    -d "{\"name\": \"TestGroup\", \"participant_ids\": $MEMBERS_JSON}" \
    $API_HOST:$API_PORT chat4all.v1.GroupService/CreateGroup 2>&1)

GROUP_ID=$(echo "$GROUP_RESPONSE" | grep -o '"group_id": *"[^"]*"' | sed 's/"group_id": *"\(.*\)"/\1/')

if [ -z "$GROUP_ID" ]; then
    echo -e "${RED}✗ Failed to create group${NC}"
    echo "$GROUP_RESPONSE"
    exit 1
fi

echo -e "${GREEN}✓ Group created: $GROUP_ID${NC}"
echo ""

# Send message to group
echo -e "${BLUE}User 1 sends message to group...${NC}"

grpcurl -plaintext \
    -H "authorization: Bearer ${TOKENS[0]}" \
    -d "{\"conversation_id\": \"$GROUP_ID\", \"content\": \"Hello group!\"}" \
    $API_HOST:$API_PORT chat4all.v1.MessageService/SendMessage > /dev/null 2>&1

echo -e "${GREEN}✓ Message sent to group${NC}"
echo ""
echo -e "${BLUE}Waiting for processing (5s)...${NC}"
sleep 5

echo ""
echo "============================================"
echo "Verification: Group Notifications"
echo "============================================"

echo ""
echo -e "${BLUE}Router Worker Logs (group processing):${NC}"
docker compose logs router-worker --tail=50 2>&1 | grep -E "group|Publishing to group member|Published notification"

echo ""
echo "Check 1: How many group member notifications?"
GROUP_NOTIF_COUNT=$(docker compose logs router-worker --tail=50 2>&1 | grep -c "Publishing to group member" || echo "0")

echo -e "${BLUE}Notifications sent to group members: $GROUP_NOTIF_COUNT${NC}"

if [ "$GROUP_NOTIF_COUNT" -ge 9 ]; then
    echo -e "${GREEN}✓ YES - At least 9 members notified (correct, sender excluded)${NC}"
elif [ "$GROUP_NOTIF_COUNT" -gt 0 ]; then
    echo -e "${YELLOW}⚠ PARTIAL - Only $GROUP_NOTIF_COUNT notifications (expected 9)${NC}"
else
    echo -e "${RED}✗ NO - No group notifications sent${NC}"
fi

echo ""
echo "Check 2: Group members in Cassandra"
echo "Checking if group was created with members..."

docker exec chat4all-cassandra cqlsh -e "SELECT group_id, member_id FROM chat4all.group_members WHERE group_id='$GROUP_ID' LIMIT 5;" 2>&1 | tail -10

echo ""
echo "============================================"
echo "FINAL SUMMARY"
echo "============================================"
echo ""

# Total notifications published
TOTAL_NOTIFS=$(docker compose logs router-worker --tail=100 2>&1 | grep -c "Published notification to Redis" || echo "0")

echo "📊 Notification Statistics:"
echo "  • Total Redis notifications: $TOTAL_NOTIFS"
echo "  • 1:1 notifications: $NOTIF_COUNT"
echo "  • Group notifications: $GROUP_NOTIF_COUNT"
echo ""

if [ "$NOTIF_COUNT" -gt 0 ] && [ "$GROUP_NOTIF_COUNT" -ge 9 ]; then
    echo -e "${GREEN}✅ BOTH TESTS PASSED${NC}"
    echo "  ✓ 1:1 notifications working"
    echo "  ✓ Group notifications working (all members notified)"
    exit 0
elif [ "$NOTIF_COUNT" -gt 0 ]; then
    echo -e "${YELLOW}⚠ PARTIAL PASS${NC}"
    echo "  ✓ 1:1 notifications working"
    echo "  ✗ Group notifications incomplete"
    exit 1
else
    echo -e "${RED}❌ TESTS FAILED${NC}"
    echo "  ✗ 1:1 notifications not working"
    exit 1
fi
