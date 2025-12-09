package chat4all.shared;

import java.time.Instant;
import java.util.Objects;

/**
 * FileEvent - Immutable POJO representing file metadata
 * 
 * Purpose: Data transfer object for file upload/storage events
 * 
 * Educational notes:
 * - Immutable design: Thread-safe, can be safely shared across services
 * - Separation of concerns: Metadata stored in Cassandra, binary in MinIO
 * - file_id uniquely identifies the file across the system
 * - storage_path references the MinIO object location
 * 
 * Lifecycle:
 * 1. Client uploads file → API generates FileEvent
 * 2. API stores binary in MinIO
 * 3. API stores FileEvent metadata in Cassandra files table
 * 4. Client receives file_id for use in messages
 * 
 * @since Phase 2 (File Storage & Connectors)
 */
public class FileEvent {
    
    // Unique identifier for this file
    private final String fileId;
    
    // Conversation context (for organization)
    private final String conversationId;
    
    // Uploader information
    private final String uploaderId;
    
    // File metadata
    private final String filename;
    private final long sizeBytes;
    private final String mimetype;
    
    // Data integrity verification
    private final String checksum;  // SHA256 hash
    
    // MinIO storage reference
    // Format: {conversation_id}/{file_id}.{extension}
    private final String storagePath;
    
    // Timestamp
    private final Instant uploadedAt;
    
    /**
     * Constructor - Creates immutable FileEvent instance
     * 
     * @param fileId Unique identifier (e.g., UUID)
     * @param conversationId Conversation this file belongs to
     * @param uploaderId User who uploaded the file
     * @param filename Original filename
     * @param sizeBytes File size in bytes
     * @param mimetype MIME type (e.g., image/jpeg, application/pdf)
     * @param checksum SHA256 checksum for integrity verification
     * @param storagePath MinIO object path
     * @param uploadedAt Upload timestamp
     */
    public FileEvent(
            String fileId,
            String conversationId,
            String uploaderId,
            String filename,
            long sizeBytes,
            String mimetype,
            String checksum,
            String storagePath,
            Instant uploadedAt) {
        
        // Validate required fields
        this.fileId = Objects.requireNonNull(fileId, "fileId cannot be null");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId cannot be null");
        this.uploaderId = Objects.requireNonNull(uploaderId, "uploaderId cannot be null");
        this.filename = Objects.requireNonNull(filename, "filename cannot be null");
        this.sizeBytes = sizeBytes;
        this.mimetype = Objects.requireNonNull(mimetype, "mimetype cannot be null");
        this.checksum = Objects.requireNonNull(checksum, "checksum cannot be null");
        this.storagePath = Objects.requireNonNull(storagePath, "storagePath cannot be null");
        this.uploadedAt = Objects.requireNonNull(uploadedAt, "uploadedAt cannot be null");
    }
    
    // Getters (no setters - immutable)
    
    public String getFileId() {
        return fileId;
    }
    
    public String getConversationId() {
        return conversationId;
    }
    
    public String getUploaderId() {
        return uploaderId;
    }
    
    public String getFilename() {
        return filename;
    }
    
    public long getSizeBytes() {
        return sizeBytes;
    }
    
    public String getMimetype() {
        return mimetype;
    }
    
    public String getChecksum() {
        return checksum;
    }
    
    public String getStoragePath() {
        return storagePath;
    }
    
    public Instant getUploadedAt() {
        return uploadedAt;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileEvent fileEvent = (FileEvent) o;
        return fileId.equals(fileEvent.fileId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(fileId);
    }
    
    @Override
    public String toString() {
        return "FileEvent{" +
                "fileId='" + fileId + '\'' +
                ", conversationId='" + conversationId + '\'' +
                ", uploaderId='" + uploaderId + '\'' +
                ", filename='" + filename + '\'' +
                ", sizeBytes=" + sizeBytes +
                ", mimetype='" + mimetype + '\'' +
                ", checksum='" + checksum + '\'' +
                ", storagePath='" + storagePath + '\'' +
                ", uploadedAt=" + uploadedAt +
                '}';
    }
}
