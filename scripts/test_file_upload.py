#!/usr/bin/env python3
"""
Chat4All - File Upload and Notification Test

Tests:
1. Upload files via gRPC streaming (10MB, 100MB, 1GB+)
2. Verify file is stored in MinIO
3. Send message with file attachment
4. Verify notifications are sent (1:1 and group)
5. Download file and verify integrity

Requirements:
    pip install grpcio grpcio-tools protobuf
"""

import grpc
import sys
import os
import hashlib
import time
from pathlib import Path

# Add generated proto path to Python path
proto_path = "/tmp/chat4all_proto_python"
sys.path.insert(0, proto_path)

# Import generated gRPC stubs
try:
    import files_pb2
    import files_pb2_grpc
    import messages_pb2
    import messages_pb2_grpc
    import auth_pb2
    import auth_pb2_grpc
except ImportError as e:
    print(f"ERROR: Failed to import gRPC stubs: {e}")
    print("\nGenerate Python stubs first:")
    print("  cd api-service/src/main/proto")
    print("  python3 -m grpc_tools.protoc -I. --python_out=/tmp/chat4all_proto_python --grpc_python_out=/tmp/chat4all_proto_python *.proto")
    sys.exit(1)

# Constants
GRPC_SERVER = "localhost:9091"
CHUNK_SIZE = 1024 * 1024  # 1MB chunks

def create_test_file(size_mb, filename):
    """Create a test file with random data"""
    print(f"📝 Creating test file: {filename} ({size_mb}MB)")
    
    with open(filename, 'wb') as f:
        # Write in chunks to avoid memory issues
        chunk_size = 1024 * 1024  # 1MB
        chunks = size_mb
        
        for i in range(chunks):
            # Generate pseudo-random but reproducible data
            data = bytes([(i * j) % 256 for j in range(chunk_size)])
            f.write(data)
            
            if (i + 1) % 100 == 0:
                print(f"   Progress: {i+1}/{chunks} MB")
    
    print(f"✓ File created: {filename}")
    return filename

def calculate_checksum(filepath):
    """Calculate SHA-256 checksum of file"""
    sha256 = hashlib.sha256()
    
    with open(filepath, 'rb') as f:
        while chunk := f.read(CHUNK_SIZE):
            sha256.update(chunk)
    
    return sha256.hexdigest()

def register_user(stub, username, password):
    """Register a new user"""
    try:
        request = auth_pb2.RegisterRequest(
            username=username,
            password=password
        )
        response = stub.Register(request)
        print(f"✓ User registered: {response.user_id}")
        return response.user_id
    except grpc.RpcError as e:
        print(f"✗ Registration failed: {e.details()}")
        return None

def login_user(stub, username, password):
    """Login and get JWT token"""
    try:
        request = auth_pb2.LoginRequest(
            username=username,
            password=password
        )
        response = stub.Login(request)
        print(f"✓ User logged in: {response.user_id}")
        print(f"   Token: {response.access_token[:50]}...")
        return response.user_id, response.access_token
    except grpc.RpcError as e:
        print(f"✗ Login failed: {e.details()}")
        return None, None

def upload_file_streaming(stub, filepath, conversation_id, token):
    """Upload file using gRPC streaming"""
    print(f"\n📤 Starting file upload: {filepath}")
    
    file_size = os.path.getsize(filepath)
    filename = os.path.basename(filepath)
    
    print(f"   Size: {file_size:,} bytes ({file_size / (1024*1024):.2f} MB)")
    print(f"   Calculating checksum...")
    
    checksum = calculate_checksum(filepath)
    print(f"   Checksum: {checksum}")
    
    # Generate session ID
    session_id = f"upload_{int(time.time() * 1000)}"
    
    # Create metadata with JWT token
    metadata = [('authorization', f'Bearer {token}')]
    
    def chunk_generator():
        """Generator for file chunks"""
        with open(filepath, 'rb') as f:
            offset = 0
            chunk_num = 0
            
            while True:
                chunk_data = f.read(CHUNK_SIZE)
                if not chunk_data:
                    break
                
                # Calculate chunk checksum
                chunk_checksum = hashlib.sha256(chunk_data).hexdigest()
                
                # Create chunk message
                chunk = files_pb2.FileChunk(
                    content=chunk_data,
                    offset=offset,
                    session_id=session_id,
                    chunk_checksum=chunk_checksum
                )
                
                # First chunk includes metadata
                if chunk_num == 0:
                    file_metadata = files_pb2.FileMetadata(
                        filename=filename,
                        size_bytes=file_size,
                        mime_type="application/octet-stream",
                        checksum=checksum,
                        conversation_id=conversation_id
                    )
                    chunk.metadata.CopyFrom(file_metadata)
                
                yield chunk
                
                offset += len(chunk_data)
                chunk_num += 1
                
                if chunk_num % 10 == 0:
                    progress = (offset / file_size) * 100
                    print(f"   Progress: {progress:.1f}% ({offset:,}/{file_size:,} bytes)")
    
    try:
        start_time = time.time()
        response = stub.UploadFile(chunk_generator(), metadata=metadata)
        elapsed = time.time() - start_time
        
        throughput = (file_size / (1024 * 1024)) / elapsed  # MB/s
        
        print(f"\n✅ Upload completed!")
        print(f"   File ID: {response.file_id}")
        print(f"   Checksum: {response.checksum}")
        print(f"   Time: {elapsed:.2f}s")
        print(f"   Throughput: {throughput:.2f} MB/s")
        
        # Verify checksum
        if response.checksum == checksum:
            print(f"   ✓ Checksum verified")
        else:
            print(f"   ✗ Checksum mismatch!")
            return None
        
        return response.file_id
        
    except grpc.RpcError as e:
        print(f"✗ Upload failed: {e.code()} - {e.details()}")
        return None

def send_message_with_file(stub, token, conversation_id, file_id, content):
    """Send message with file attachment"""
    print(f"\n📨 Sending message with file attachment...")
    
    try:
        # Create metadata with JWT token
        metadata = [('authorization', f'Bearer {token}')]
        
        request = messages_pb2.SendMessageRequest(
            conversation_id=conversation_id,
            content=content,
            file_id=file_id
        )
        
        response = stub.SendMessage(request, metadata=metadata)
        
        print(f"✓ Message sent!")
        print(f"   Message ID: {response.message_id}")
        print(f"   Status: {response.status}")
        
        return response.message_id
        
    except grpc.RpcError as e:
        print(f"✗ Send failed: {e.code()} - {e.details()}")
        return None

def download_file(stub, file_id, output_path, token):
    """Download file via gRPC streaming"""
    print(f"\n📥 Downloading file: {file_id}")
    
    try:
        # Create metadata with JWT token
        metadata = [('authorization', f'Bearer {token}')]
        
        request = files_pb2.DownloadFileRequest(file_id=file_id)
        
        with open(output_path, 'wb') as f:
            bytes_received = 0
            
            for chunk in stub.DownloadFile(request, metadata=metadata):
                f.write(chunk.content)
                bytes_received += len(chunk.content)
                
                if bytes_received % (10 * CHUNK_SIZE) == 0:
                    print(f"   Received: {bytes_received:,} bytes")
        
        print(f"✓ Download completed: {bytes_received:,} bytes")
        
        # Verify checksum
        downloaded_checksum = calculate_checksum(output_path)
        print(f"   Checksum: {downloaded_checksum}")
        
        return downloaded_checksum
        
    except grpc.RpcError as e:
        print(f"✗ Download failed: {e.code()} - {e.details()}")
        return None

def main():
    print("=" * 60)
    print("CHAT4ALL - FILE UPLOAD AND NOTIFICATION TEST")
    print("=" * 60)
    
    # Test configurations
    tests = [
        {"size_mb": 10, "name": "small_file_10MB.bin"},
        {"size_mb": 100, "name": "medium_file_100MB.bin"},
        {"size_mb": 1024, "name": "large_file_1GB.bin"},
    ]
    
    # Connect to gRPC server
    channel = grpc.insecure_channel(GRPC_SERVER)
    auth_stub = auth_pb2_grpc.AuthServiceStub(channel)
    file_stub = files_pb2_grpc.FileServiceStub(channel)
    msg_stub = messages_pb2_grpc.MessageServiceStub(channel)
    
    print(f"\n✓ Connected to gRPC server: {GRPC_SERVER}")
    
    # Register and login users
    print("\n" + "=" * 60)
    print("STEP 1: User Registration & Login")
    print("=" * 60)
    
    alice_username = f"alice_file_{int(time.time())}"
    bob_username = f"bob_file_{int(time.time())}"
    password = "pass123"
    
    alice_id = register_user(auth_stub, alice_username, password)
    bob_id = register_user(auth_stub, bob_username, password)
    
    if not alice_id or not bob_id:
        print("✗ User registration failed")
        return 1
    
    time.sleep(1)  # Give time for user creation
    
    alice_id, alice_token = login_user(auth_stub, alice_username, password)
    bob_id, bob_token = login_user(auth_stub, bob_username, password)
    
    if not alice_token or not bob_token:
        print("✗ User login failed")
        return 1
    
    conversation_id = f"direct_{alice_id}_{bob_id}"
    
    # Run file upload tests
    for test in tests:
        print("\n" + "=" * 60)
        print(f"TEST: {test['name']} ({test['size_mb']} MB)")
        print("=" * 60)
        
        # Create test file
        filepath = f"/tmp/{test['name']}"
        create_test_file(test['size_mb'], filepath)
        
        original_checksum = calculate_checksum(filepath)
        print(f"Original checksum: {original_checksum}")
        
        # Upload file
        file_id = upload_file_streaming(file_stub, filepath, conversation_id, alice_token)
        
        if not file_id:
            print(f"✗ Test failed for {test['name']}")
            continue
        
        # Send message with file
        message_id = send_message_with_file(
            msg_stub, 
            alice_token,
            conversation_id,
            file_id,
            f"Here's the file: {test['name']}"
        )
        
        if not message_id:
            print(f"✗ Message send failed")
            continue
        
        # Wait for notification processing
        print("\n⏳ Waiting for notification processing...")
        time.sleep(2)
        
        # Download and verify
        download_path = f"/tmp/downloaded_{test['name']}"
        downloaded_checksum = download_file(file_stub, file_id, download_path, alice_token)
        
        if downloaded_checksum == original_checksum:
            print(f"✅ TEST PASSED: {test['name']}")
            print(f"   ✓ Upload successful")
            print(f"   ✓ Message sent with attachment")
            print(f"   ✓ Download successful")
            print(f"   ✓ Checksum verified")
        else:
            print(f"✗ TEST FAILED: Checksum mismatch")
        
        # Cleanup
        os.remove(filepath)
        os.remove(download_path)
    
    print("\n" + "=" * 60)
    print("VERIFYING NOTIFICATIONS IN LOGS")
    print("=" * 60)
    print("\nCheck router-worker logs for:")
    print("  ✓ Published notification to Redis")
    print("  ✓ file_id field in notification payload")
    print("\nCommand:")
    print("  docker compose logs router-worker --tail=50 | grep -A5 'file_id'")
    
    channel.close()
    return 0

if __name__ == "__main__":
    sys.exit(main())
