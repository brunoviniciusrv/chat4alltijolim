package chat4all.api.grpc.service;

import chat4all.api.cassandra.CassandraMessageRepository;
import chat4all.api.grpc.interceptor.AuthInterceptor;
import chat4all.api.kafka.MessageProducer;
import chat4all.api.metrics.PrometheusMetricsServer;
import chat4all.grpc.generated.v1.*;
import chat4all.shared.tracing.TracingUtils;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MessageServiceImpl extends MessageServiceGrpc.MessageServiceImplBase {
    
    private static final Logger log = LoggerFactory.getLogger(MessageServiceImpl.class);
    private final MessageProducer messageProducer;
    private final CassandraMessageRepository repository;
    private final Tracer tracer;
    private final PrometheusMetricsServer metricsServer;
    
    public MessageServiceImpl(MessageProducer messageProducer, CassandraMessageRepository repository, Tracer tracer, PrometheusMetricsServer metricsServer) {
        this.messageProducer = messageProducer;
        this.repository = repository;
        this.tracer = tracer;
        this.metricsServer = metricsServer;
    }
    
    @Override
    public void sendMessage(SendMessageRequest request, StreamObserver<SendMessageResponse> responseObserver) {
        long startTime = System.currentTimeMillis();
        metricsServer.incrementRequests();
        
        Span span = tracer.spanBuilder("MessageService/SendMessage").startSpan();
        try (var scope = span.makeCurrent()) {
            String userId = AuthInterceptor.USER_ID.get(Context.current());
            String conversationId = request.getConversationId();
            String content = request.getContent();
            
            // Add tracing attributes
            span.setAttribute("user_id", userId);
            span.setAttribute("conversation_id", conversationId);
            span.setAttribute("message_length", content.length());
            
            if (conversationId.isEmpty() || content.isEmpty()) {
                span.recordException(new IllegalArgumentException("Missing required fields"));
                metricsServer.incrementFailedRequests();
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Missing required fields").asRuntimeException());
                return;
            }
            
            String messageId = "msg_" + UUID.randomUUID().toString();
            long timestamp = System.currentTimeMillis();
            
            span.setAttribute("message_id", messageId);
            span.addEvent("message_created");
            
            String messageJson = String.format(
                "{\"message_id\":\"%s\",\"conversation_id\":\"%s\",\"sender_id\":\"%s\",\"content\":\"%s\",\"timestamp\":%d,\"status\":\"ACCEPTED\",\"event_type\":\"MESSAGE_SENT\",\"trace_id\":\"%s\"}",
                messageId, conversationId, userId, content.replace("\"", "\\\""), timestamp, TracingUtils.getCurrentTraceId()
            );
            
            // Publish to Kafka and wait for confirmation
            log.info("Publishing message {} to Kafka topic...", messageId);
            span.addEvent("kafka_publish_start");
            try {
                java.util.concurrent.Future<org.apache.kafka.clients.producer.RecordMetadata> future = 
                    messageProducer.publish(conversationId, messageJson);
                log.info("Waiting for Kafka acknowledgment...");
                org.apache.kafka.clients.producer.RecordMetadata metadata = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
                log.info("✅ Message published to Kafka: {} (partition={}, offset={})", 
                    messageId, metadata.partition(), metadata.offset());
                span.addEvent("kafka_publish_success");
                span.setAttribute("kafka.partition", metadata.partition());
                span.setAttribute("kafka.offset", metadata.offset());
                metricsServer.incrementMessagesSent();
            } catch (java.util.concurrent.TimeoutException te) {
                log.error("❌ Timeout waiting for Kafka: {}", messageId, te);
                span.recordException(te);
                metricsServer.incrementFailedRequests();
            } catch (Exception publishException) {
                log.error("❌ Failed to publish message to Kafka: {}", messageId, publishException);
                span.recordException(publishException);
                metricsServer.incrementFailedRequests();
            }
            
            SendMessageResponse response = SendMessageResponse.newBuilder()
                .setMessageId(messageId)
                .setConversationId(conversationId)
                .setStatus("ACCEPTED")
                .setTimestamp(timestamp)
                .build();
            
            log.info("✓ Message sent: {}", messageId);
            span.addEvent("response_sent");
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
            // Record latency
            long duration = System.currentTimeMillis() - startTime;
            metricsServer.recordDuration("SendMessage", duration);
            
        } catch (Exception e) {
            log.error("❌ Send message failed", e);
            span.recordException(e);
            metricsServer.incrementFailedRequests();
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        } finally {
            span.end();
        }
    }
    
    @Override
    public void getMessages(GetMessagesRequest request, StreamObserver<GetMessagesResponse> responseObserver) {
        try {
            String conversationId = request.getConversationId();
            int limit = Math.min(request.getLimit() > 0 ? request.getLimit() : 50, 100);
            int offset = (int) request.getOffset();
            
            List<Map<String, Object>> messages = repository.getMessages(conversationId, limit, offset);
            
            GetMessagesResponse.Builder builder = GetMessagesResponse.newBuilder()
                .setConversationId(conversationId);
            
            for (Map<String, Object> msg : messages) {
                Message protoMsg = Message.newBuilder()
                    .setMessageId((String) msg.get("message_id"))
                    .setConversationId((String) msg.get("conversation_id"))
                    .setSenderId((String) msg.get("sender_id"))
                    .setContent((String) msg.get("content"))
                    .setStatus((String) msg.get("status"))
                    .setTimestamp((Long) msg.get("timestamp"))
                    .setFileId(msg.get("file_id") != null ? (String) msg.get("file_id") : "")
                    .build();
                
                builder.addMessages(protoMsg);
            }
            
            builder.setPagination(Pagination.newBuilder()
                .setOffset(offset)
                .setLimit(limit)
                .setReturned(messages.size())
                .setHasMore(messages.size() >= limit)
                .build());
            
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            log.error("❌ Get messages failed", e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }
    
    @Override
    public void markAsRead(MarkAsReadRequest request, StreamObserver<MarkAsReadResponse> responseObserver) {
        try {
            repository.updateMessageStatus(request.getMessageId(), "READ", System.currentTimeMillis());
            
            MarkAsReadResponse response = MarkAsReadResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Message marked as read")
                .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            log.error("❌ Mark as read failed", e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }
    
    @Override
    public StreamObserver<SendMessageRequest> streamMessages(StreamObserver<MessageNotification> responseObserver) {
        return new StreamObserver<SendMessageRequest>() {
            @Override
            public void onNext(SendMessageRequest request) {
                // Simplified - just echo back
                log.info("📨 Stream message received");
            }
            
            @Override
            public void onError(Throwable t) {
                log.error("❌ Stream error", t);
            }
            
            @Override
            public void onCompleted() {
                responseObserver.onCompleted();
            }
        };
    }
}
