#!/bin/bash

# Cassandra Schema Initialization Script
#
# EDUCATIONAL PURPOSE:
# This script demonstrates DATABASE SCHEMA INITIALIZATION in containerized environments.
# 
# THE PROBLEM:
# Docker Compose starts Cassandra container, but the database is EMPTY.
# Our application needs tables (messages, conversations, users) to exist before it can write data.
#
# SOLUTION: Init Container Pattern
# 1. Wait for Cassandra to be ready (accepting CQL connections)
# 2. Execute schema.cql to create keyspace and tables
# 3. Signal success (exit 0) so application containers can start
#
# WHY NOT JUST RUN "cqlsh < schema.cql"?
# - Cassandra takes 30-60 seconds to start (JVM initialization, gossip protocol)
# - If we run cqlsh too early, connection fails
# - Need retry logic with exponential backoff
#
# PRODUCTION ALTERNATIVES:
# - Flyway / Liquibase: Schema versioning and migration tools
# - Kubernetes Init Containers: Run before app containers
# - Configuration Management: Ansible, Terraform provision databases

set -e  # Exit immediately if any command fails

# =====================
# CONFIGURATION
# =====================

CASSANDRA_HOST="${CASSANDRA_HOST:-cassandra}"  # Docker Compose service name
CASSANDRA_PORT="${CASSANDRA_PORT:-9042}"       # CQL native protocol port
SCHEMA_FILE="/docker-entrypoint-initdb.d/schema.cql"  # Mounted from host

MAX_RETRIES=30      # Maximum retry attempts
RETRY_DELAY=2       # Seconds between retries

# =====================
# FUNCTIONS
# =====================

# Log message with timestamp
log() {
    echo "[$(date +'%Y-%m-%d %H:%M:%S')] $*"
}

# Check if Cassandra is accepting connections
# Returns 0 (success) if ready, 1 (failure) if not ready
check_cassandra_ready() {
    cqlsh "$CASSANDRA_HOST" "$CASSANDRA_PORT" -e "DESCRIBE KEYSPACES" > /dev/null 2>&1
    return $?
}

# Wait for Cassandra to be ready
wait_for_cassandra() {
    log "Waiting for Cassandra at $CASSANDRA_HOST:$CASSANDRA_PORT..."
    
    for attempt in $(seq 1 $MAX_RETRIES); do
        if check_cassandra_ready; then
            log "✓ Cassandra is ready (attempt $attempt/$MAX_RETRIES)"
            return 0
        else
            log "  Cassandra not ready yet (attempt $attempt/$MAX_RETRIES), retrying in ${RETRY_DELAY}s..."
            sleep $RETRY_DELAY
        fi
    done
    
    log "✗ ERROR: Cassandra did not become ready after $MAX_RETRIES attempts"
    return 1
}

# Execute schema.cql to create keyspace and tables
initialize_schema() {
    log "Executing schema initialization..."
    
    if [ ! -f "$SCHEMA_FILE" ]; then
        log "✗ ERROR: Schema file not found at $SCHEMA_FILE"
        return 1
    fi
    
    # Execute schema.cql
    # EDUCATIONAL NOTE: cqlsh reads the file and executes each CQL statement
    # This is idempotent: IF NOT EXISTS prevents errors if tables already exist
    if cqlsh "$CASSANDRA_HOST" "$CASSANDRA_PORT" -f "$SCHEMA_FILE"; then
        log "✓ Schema initialized successfully"
        return 0
    else
        log "✗ ERROR: Schema initialization failed"
        return 1
    fi
}

# Verify schema was created correctly
verify_schema() {
    log "Verifying schema..."
    
    # Check if keyspace exists
    if ! cqlsh "$CASSANDRA_HOST" "$CASSANDRA_PORT" -e "DESCRIBE KEYSPACE chat4all" > /dev/null 2>&1; then
        log "✗ ERROR: Keyspace 'chat4all' was not created"
        return 1
    fi
    
    # Check if messages table exists
    if ! cqlsh "$CASSANDRA_HOST" "$CASSANDRA_PORT" -e "DESCRIBE TABLE chat4all.messages" > /dev/null 2>&1; then
        log "✗ ERROR: Table 'chat4all.messages' was not created"
        return 1
    fi
    
    log "✓ Schema verification successful"
    return 0
}

# =====================
# MAIN EXECUTION
# =====================

log "========================================="
log "Cassandra Schema Initialization"
log "========================================="

# Step 1: Wait for Cassandra to be ready
if ! wait_for_cassandra; then
    log "✗ FATAL: Cannot connect to Cassandra, exiting"
    exit 1
fi

# Step 2: Initialize schema
if ! initialize_schema; then
    log "✗ FATAL: Schema initialization failed, exiting"
    exit 1
fi

# Step 3: Verify schema
if ! verify_schema; then
    log "✗ FATAL: Schema verification failed, exiting"
    exit 1
fi

log "========================================="
log "✓ Schema initialization complete!"
log "========================================="

# Exit successfully (signals Docker that init is done)
exit 0
