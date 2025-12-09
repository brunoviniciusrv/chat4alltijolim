package chat4all.api.kafka;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;
import java.util.concurrent.Future;

/**
 * MessageProducer - Publishes messages to Kafka topic
 * 
 * EDUCATIONAL PURPOSE: Kafka Producer Pattern
 * ==================
 * 
 * WHY KAFKA FOR MESSAGING?
 * 1. **Decoupling**: API and Worker run independently
 * 2. **Reliability**: Messages persist in Kafka (no data loss if worker crashes)
 * 3. **Scalability**: Multiple workers can consume in parallel
 * 4. **Ordering**: Messages in same partition are ordered
 * 
 * KAFKA CONCEPTS:
 * - **Topic**: Named channel (like "messages")
 * - **Partition**: Sub-division of topic for parallelism
 * - **Key**: Determines which partition (same key → same partition → ordered)
 * - **Value**: Actual message content (JSON string)
 * 
 * PRODUCER CONFIG:
 * - bootstrap.servers: Kafka broker addresses
 * - key.serializer: How to convert key to bytes (StringSerializer)
 * - value.serializer: How to convert value to bytes (StringSerializer)
 * - acks: Acknowledgment level (1 = leader confirms write)
 * 
 * EDUCATIONAL NOTE ON ACKS:
 * - acks=0: Fire-and-forget (fastest, may lose data)
 * - acks=1: Leader confirms (balanced, default)
 * - acks=all: All replicas confirm (slowest, safest)
 * 
 * @author Chat4All Educational Project
 */
public class MessageProducer {
    
    private final KafkaProducer<String, String> producer;
    private final String topic;
    
    /**
     * Creates a MessageProducer
     * 
     * @param bootstrapServers Kafka broker addresses (e.g., "localhost:9092")
     * @param topic Topic name (e.g., "messages")
     */
    public MessageProducer(String bootstrapServers, String topic) {
        this.topic = topic;
        this.producer = createProducer(bootstrapServers);
    }
    
    /**
     * Creates and configures Kafka producer
     * 
     * CONFIGURATION EXPLAINED:
     * 
     * 1. bootstrap.servers: Initial broker list for connection
     *    - Producer discovers full cluster automatically
     *    - Format: "host1:port1,host2:port2"
     * 
     * 2. key.serializer: Converts key object to bytes
     *    - We use StringSerializer (key = conversation_id)
     * 
     * 3. value.serializer: Converts value object to bytes
     *    - We use StringSerializer (value = JSON string)
     * 
     * 4. acks: Write acknowledgment level
     *    - "1" = Leader replica acknowledges (fast + reliable)
     * 
     * 5. retries: Number of retry attempts on failure
     *    - 3 retries covers transient network issues
     * 
     * 6. linger.ms: Wait time before sending batch
     *    - 10ms allows batching for throughput
     * 
     * @param bootstrapServers Kafka brokers
     * @return Configured KafkaProducer
     */
    private KafkaProducer<String, String> createProducer(String bootstrapServers) {
        Properties props = new Properties();
        
        // Broker connection
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        
        // Serializers (convert objects to bytes)
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        
        // Acknowledgment: Wait for leader replica to confirm write
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        
        // Retry failed sends up to 3 times
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        
        // Wait 10ms before sending batch (improves throughput)
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        
        // Client ID for monitoring/debugging
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "chat4all-api-producer");
        
        return new KafkaProducer<>(props);
    }
    
    /**
     * Publishes a message to Kafka (async)
     * 
     * KEY PARTITIONING STRATEGY:
     * - Key = conversation_id
     * - Same conversation_id always goes to same partition
     * - Messages in same conversation are ordered
     * - Different conversations can be processed in parallel
     * 
     * FLOW:
     * 1. Create ProducerRecord(topic, key, value)
     * 2. Send to producer (async, returns Future)
     * 3. Producer batches messages for efficiency
     * 4. Producer sends batch to Kafka broker
     * 5. Broker writes to partition and returns metadata
     * 
     * EDUCATIONAL NOTE ON ASYNC:
     * - send() returns immediately (doesn't wait for Kafka)
     * - Future.get() blocks until write completes (if you want to wait)
     * - For high throughput, don't call get() - fire-and-forget
     * 
     * @param conversationId Conversation ID (used as partition key)
     * @param messageJson Message JSON string
     * @return Future with RecordMetadata (partition, offset)
     */
    public Future<RecordMetadata> publish(String conversationId, String messageJson) {
        // Create record: topic, key (for partitioning), value (message data)
        ProducerRecord<String, String> record = new ProducerRecord<>(
            topic,           // Topic name
            conversationId,  // Key (determines partition)
            messageJson      // Value (actual message data)
        );
        
        // Send async (returns Future immediately)
        // Producer will batch and send to Kafka
        return producer.send(record);
    }
    
    /**
     * Publishes a message to Kafka (async) with callback
     * 
     * CALLBACK VERSION:
     * - Executes callback when send completes (success or failure)
     * - Use for logging, metrics, error handling
     * - Non-blocking (doesn't wait for completion)
     * 
     * @param conversationId Conversation ID (partition key)
     * @param messageJson Message JSON string
     * @param callback Callback executed on completion
     */
    public void publish(String conversationId, String messageJson, 
                       org.apache.kafka.clients.producer.Callback callback) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
            topic,
            conversationId,
            messageJson
        );
        
        producer.send(record, callback);
    }
    
    /**
     * Flushes all pending messages
     * 
     * WHEN TO USE:
     * - Before shutdown (ensure all messages sent)
     * - After critical messages (guarantee delivery)
     * - In tests (ensure messages published before assertions)
     * 
     * BLOCKS until all buffered messages are sent.
     */
    public void flush() {
        producer.flush();
    }
    
    /**
     * Closes producer and releases resources
     * 
     * CLEANUP SEQUENCE:
     * 1. Flush all pending messages
     * 2. Close connection to Kafka
     * 3. Release network resources
     * 
     * Always call in shutdown hook or finally block.
     */
    public void close() {
        producer.close();
    }
}
