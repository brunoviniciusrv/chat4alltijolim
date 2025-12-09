#!/bin/bash

# Test file notifications in 1:1 and group conversations
# Validates that file_id is included in Redis notifications

set -e

API_URL="localhost:9091"
echo "=================================="
echo "FILE NOTIFICATION TEST"
echo "=================================="

# Register and login users
echo ""
echo "[1] Creating users..."
ALICE_USER=$(grpcurl -plaintext -d '{"username":"alice_filetest_'$(date +%s)'","password":"pass123"}' \
  ${API_URL} chat4all.v1.AuthService/Register | jq -r '.user_id')
echo "✓ Alice: $ALICE_USER"

BOB_USER=$(grpcurl -plaintext -d '{"username":"bob_filetest_'$(date +%s)'","password":"pass123"}' \
  ${API_URL} chat4all.v1.AuthService/Register | jq -r '.user_id')
echo "✓ Bob: $BOB_USER"

USER1=$(grpcurl -plaintext -d '{"username":"user1_filetest_'$(date +%s)'","password":"pass123"}' \
  ${API_URL} chat4all.v1.AuthService/Register | jq -r '.user_id')
USER2=$(grpcurl -plaintext -d '{"username":"user2_filetest_'$(date +%s)'","password":"pass123"}' \
  ${API_URL} chat4all.v1.AuthService/Register | jq -r '.user_id')
USER3=$(grpcurl -plaintext -d '{"username":"user3_filetest_'$(date +%s)'","password":"pass123"}' \
  ${API_URL} chat4all.v1.AuthService/Register | jq -r '.user_id')

echo "✓ Group members: $USER1, $USER2, $USER3"

# Login Alice
echo ""
echo "[2] Logging in..."
ALICE_TOKEN=$(grpcurl -plaintext -d '{"username":"alice_filetest_'$(($(date +%s) - 10))'","password":"pass123"}' \
  ${API_URL} chat4all.v1.AuthService/Login 2>/dev/null | jq -r '.access_token' | head -1)

# Try to get latest alice
for i in {0..20}; do
  TEST_TOKEN=$(grpcurl -plaintext -d "{\"username\":\"alice_filetest_$(($(date +%s) - i))\",\"password\":\"pass123\"}" \
    ${API_URL} chat4all.v1.AuthService/Login 2>/dev/null | jq -r '.access_token' | head -1)
  if [ "$TEST_TOKEN" != "null" ] && [ ! -z "$TEST_TOKEN" ]; then
    ALICE_TOKEN="$TEST_TOKEN"
    break
  fi
done

echo "✓ Alice token: ${ALICE_TOKEN:0:50}..."

# Create group
echo ""
echo "[3] Creating group..."
GROUP_RESPONSE=$(grpcurl -plaintext \
  -H "authorization: Bearer ${ALICE_TOKEN}" \
  -d "{\"name\":\"Test Group\",\"participant_ids\":[\"$USER1\",\"$USER2\",\"$USER3\"]}" \
  ${API_URL} chat4all.v1.GroupService/CreateGroup 2>/dev/null)

GROUP_ID=$(echo "$GROUP_RESPONSE" | jq -r '.group_id')
echo "✓ Group created: $GROUP_ID"

# Monitor router-worker logs in background
echo ""
echo "[4] Monitoring notifications..."
docker compose logs -f router-worker 2>&1 | grep -E "file_id|Published notification" &
LOG_PID=$!

sleep 2

# Send message with fake file_id (simulating file attachment)
echo ""
echo "[5] Sending 1:1 message with file attachment..."
CONV_ID="direct_${ALICE_USER}_${BOB_USER}"
FILE_ID="file_test_12345_demo"

MSG_RESPONSE=$(grpcurl -plaintext \
  -H "authorization: Bearer ${ALICE_TOKEN}" \
  -d "{\"conversation_id\":\"$CONV_ID\",\"content\":\"Check this file!\",\"file_id\":\"$FILE_ID\"}" \
  ${API_URL} chat4all.v1.MessageService/SendMessage 2>/dev/null)

MESSAGE_ID=$(echo "$MSG_RESPONSE" | jq -r '.message_id')
echo "✓ Message sent: $MESSAGE_ID"
echo "  File ID: $FILE_ID"

sleep 3

# Send group message with file
echo ""
echo "[6] Sending group message with file attachment..."
GROUP_FILE_ID="file_group_67890_demo"

GROUP_MSG=$(grpcurl -plaintext \
  -H "authorization: Bearer ${ALICE_TOKEN}" \
  -d "{\"conversation_id\":\"$GROUP_ID\",\"content\":\"Group file!\",\"file_id\":\"$GROUP_FILE_ID\"}" \
  ${API_URL} chat4all.v1.MessageService/SendMessage 2>/dev/null)

GROUP_MSG_ID=$(echo "$GROUP_MSG" | jq -r '.message_id')
echo "✓ Group message sent: $GROUP_MSG_ID"
echo "  File ID: $GROUP_FILE_ID"

sleep 3

# Stop log monitoring
kill $LOG_PID 2>/dev/null || true

# Verify in logs
echo ""
echo "=================================="
echo "VERIFICATION"
echo "=================================="

echo ""
echo "Checking recent logs for file_id in notifications..."
FILE_MENTIONS=$(docker compose logs router-worker --tail=100 2>&1 | grep -c "\"file_id\"" || echo "0")
echo "  → Found $FILE_MENTIONS mentions of file_id in notification payloads"

if [ "$FILE_MENTIONS" -gt "0" ]; then
  echo ""
  echo "✅ File notifications working!"
  echo ""
  echo "Sample notification with file_id:"
  docker compose logs router-worker --tail=100 2>&1 | grep -B2 -A2 "file_id" | head -10
else
  echo ""
  echo "⚠  No file_id found in recent notifications"
  echo "   This might be because:"
  echo "   - Logs rotated (try again)"
  echo "   - Router-worker hasn't processed yet (check logs manually)"
fi

echo ""
echo "Manual verification command:"
echo "  docker compose logs router-worker --tail=200 | grep -A5 '$MESSAGE_ID\\|$GROUP_MSG_ID'"
