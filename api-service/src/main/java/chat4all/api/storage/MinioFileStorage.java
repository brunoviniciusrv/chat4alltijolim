package chat4all.api.storage;

import io.minio.*;
import io.minio.http.Method;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * MinioFileStorage - File storage service using MinIO (S3-compatible)
 */
public class MinioFileStorage {
    
    private final MinioClient minioClient;
    private final String bucketName;
    
    public MinioFileStorage(String endpoint, String accessKey, String secretKey) {
        this.minioClient = MinioClient.builder()
            .endpoint(endpoint)
            .credentials(accessKey, secretKey)
            .build();
        
        this.bucketName = System.getenv().getOrDefault("MINIO_BUCKET", "chat4all-files");
        
        // Ensure bucket exists
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize MinIO bucket", e);
        }
    }
    
    /**
     * Upload file result
     */
    public static class UploadResult {
        private final String fileId;
        private final String filename;
        private final long sizeBytes;
        private final String checksum;
        private final String storagePath;
        
        public UploadResult(String fileId, String filename, long sizeBytes, String checksum, String storagePath) {
            this.fileId = fileId;
            this.filename = filename;
            this.sizeBytes = sizeBytes;
            this.checksum = checksum;
            this.storagePath = storagePath;
        }
        
        public String getFileId() { return fileId; }
        public String getFilename() { return filename; }
        public long getSizeBytes() { return sizeBytes; }
        public String getChecksum() { return checksum; }
        public String getStoragePath() { return storagePath; }
    }
    
    /**
     * Upload file to MinIO
     */
    public UploadResult uploadFile(String filename, byte[] data, String mimeType, String conversationId) {
        try {
            String fileId = "file_" + UUID.randomUUID().toString();
            String objectName = conversationId + "/" + fileId + "_" + filename;
            
            // Calculate checksum
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(data);
            StringBuilder checksum = new StringBuilder();
            for (byte b : hashBytes) {
                checksum.append(String.format("%02x", b));
            }
            
            // Upload to MinIO
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .contentType(mimeType)
                    .build()
            );
            
            return new UploadResult(fileId, filename, data.length, checksum.toString(), objectName);
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload file", e);
        }
    }
    
    /**
     * Download file from MinIO (deprecated - use downloadFileByPath)
     */
    public InputStream downloadFile(String fileId) {
        throw new UnsupportedOperationException("Use downloadFileByPath with storage_path from metadata");
    }
    
    /**
     * Download de arquivo usando storage_path diretamente
     * 
     * @param storagePath Path completo no MinIO (conversation_id/file_id_filename)
     * @return InputStream do arquivo
     */
    public InputStream downloadFileByPath(String storagePath) {
        try {
            System.out.println("📥 Downloading from MinIO: " + storagePath);
            
            GetObjectArgs args = GetObjectArgs.builder()
                .bucket(bucketName)
                .object(storagePath)
                .build();
            
            InputStream stream = minioClient.getObject(args);
            System.out.println("✅ Stream opened successfully");
            
            return stream;
            
        } catch (Exception e) {
            System.err.println("❌ MinIO download error: " + e.getMessage());
            throw new RuntimeException("Failed to download file from MinIO: " + storagePath, e);
        }
    }
    
    /**
     * Get presigned download URL (valid for 1 hour)
     */
    public String getPresignedUrl(String objectName) {
        try {
            return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucketName)
                    .object(objectName)
                    .expiry(3600) // 1 hour
                    .build()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate presigned URL", e);
        }
    }
}
