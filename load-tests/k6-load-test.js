/**
 * ============================================================================
 * Chat4All - K6 Load Test Script
 * ============================================================================
 * Tests: Multiple concurrent users sending messages
 * Metrics: Throughput, latency, errors
 * ============================================================================
 */

import grpc from 'k6/net/grpc';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// Custom metrics
const errorRate = new Rate('errors');
const messageLatency = new Trend('message_send_latency');
const messagesProcessed = new Counter('messages_processed');

// Test configuration
export const options = {
  stages: [
    { duration: '30s', target: 10 },   // Ramp-up to 10 users
    { duration: '1m', target: 50 },    // Ramp-up to 50 users
    { duration: '2m', target: 100 },   // Ramp-up to 100 users
    { duration: '2m', target: 100 },   // Stay at 100 users
    { duration: '30s', target: 0 },    // Ramp-down to 0
  ],
  thresholds: {
    'errors': ['rate<0.1'],                    // Error rate < 10%
    'message_send_latency': ['p(95)<500'],     // 95% of requests < 500ms
    'grpc_req_duration': ['p(99)<1000'],       // 99% of requests < 1s
  },
};

const client = new grpc.Client();
client.load(['../api-service/src/main/proto'], 'auth.proto', 'messages.proto');

let authToken = '';

export function setup() {
  // Connect to gRPC server
  client.connect('localhost:9091', { plaintext: true });
  
  // Create test user and get auth token
  const createUserResp = client.invoke('chat4all.auth.AuthService/CreateUser', {
    user_id: `user_load_${Date.now()}`,
    username: `loadtest_${Date.now()}`,
    password: 'password123'
  });
  
  if (createUserResp.status === grpc.StatusOK) {
    const loginResp = client.invoke('chat4all.auth.AuthService/Login', {
      username: createUserResp.message.username,
      password: 'password123'
    });
    
    if (loginResp.status === grpc.StatusOK) {
      return { token: loginResp.message.access_token };
    }
  }
  
  throw new Error('Failed to setup test user');
}

export default function(data) {
  const metadata = {
    'authorization': `Bearer ${data.token}`
  };
  
  const conversationId = `conv_load_${__VU}_${Date.now()}`;
  const content = `Load test message from VU ${__VU} at ${new Date().toISOString()}`;
  
  const startTime = Date.now();
  
  const response = client.invoke(
    'chat4all.messages.MessageService/SendMessage',
    {
      conversation_id: conversationId,
      content: content
    },
    { metadata: metadata }
  );
  
  const duration = Date.now() - startTime;
  messageLatency.add(duration);
  
  const success = check(response, {
    'status is OK': (r) => r && r.status === grpc.StatusOK,
    'message_id returned': (r) => r && r.message && r.message.message_id !== '',
  });
  
  if (success) {
    messagesProcessed.add(1);
  } else {
    errorRate.add(1);
    console.error(`Failed to send message: ${response.error}`);
  }
  
  // Random think time between 100ms and 500ms
  sleep(Math.random() * 0.4 + 0.1);
}

export function teardown(data) {
  client.close();
  
  console.log('='.repeat(80));
  console.log('LOAD TEST SUMMARY');
  console.log('='.repeat(80));
  console.log(`Total messages processed: ${messagesProcessed.count}`);
  console.log(`Error rate: ${(errorRate.rate * 100).toFixed(2)}%`);
  console.log(`Average latency: ${messageLatency.avg.toFixed(2)}ms`);
  console.log(`P95 latency: ${messageLatency.p(0.95).toFixed(2)}ms`);
  console.log(`P99 latency: ${messageLatency.p(0.99).toFixed(2)}ms`);
  console.log('='.repeat(80));
}
