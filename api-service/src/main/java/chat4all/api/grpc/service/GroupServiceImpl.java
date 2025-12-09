package chat4all.api.grpc.service;

import chat4all.api.cassandra.CassandraMessageRepository;
import chat4all.api.grpc.interceptor.AuthInterceptor;
import chat4all.grpc.generated.v1.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.UUID;

public class GroupServiceImpl extends GroupServiceGrpc.GroupServiceImplBase {
    
    private final CassandraMessageRepository repository;
    private final AuthInterceptor interceptor;
    
    public GroupServiceImpl(CassandraMessageRepository repository, AuthInterceptor interceptor) {
        this.repository = repository;
        this.interceptor = interceptor;
    }
    
    @Override
    public void createGroup(CreateGroupRequest request, StreamObserver<CreateGroupResponse> responseObserver) {
        try {
            // Gerar ID do grupo
            String groupId = "group_" + UUID.randomUUID().toString();
            long timestamp = System.currentTimeMillis();
            
            // Validar tipo
            String type = request.getType().isEmpty() ? "GROUP" : request.getType();
            if (!type.equals("DIRECT") && !type.equals("GROUP")) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Type must be DIRECT or GROUP")
                    .asRuntimeException());
                return;
            }
            
            // Create group in Cassandra
            System.out.println("📝 Creating group: " + request.getName());
            System.out.println("   Type: " + type);
            System.out.println("   Participants: " + request.getParticipantIdsList().size());
            
            repository.createGroup(groupId, request.getName(), request.getParticipantIdsList(), type);
            
            CreateGroupResponse response = CreateGroupResponse.newBuilder()
                .setGroupId(groupId)
                .setName(request.getName())
                .addAllParticipantIds(request.getParticipantIdsList())
                .setType(type)
                .setCreatedAt(timestamp)
                .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                .withDescription("Error creating group: " + e.getMessage())
                .asRuntimeException());
        }
    }
    
    @Override
    public void getGroup(GetGroupRequest request, StreamObserver<GetGroupResponse> responseObserver) {
        try {
            String groupId = request.getGroupId();
            
            System.out.println("🔍 Getting group info: " + groupId);
            
            var groupOpt = repository.getGroup(groupId);
            if (groupOpt.isEmpty()) {
                responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Group not found: " + groupId)
                    .asRuntimeException());
                return;
            }
            
            var group = groupOpt.get();
            GetGroupResponse response = GetGroupResponse.newBuilder()
                .setGroupId(group.getGroupId())
                .setName(group.getName())
                .addAllParticipantIds(group.getParticipantIds())
                .setType(group.getType())
                .setCreatedAt(group.getCreatedAt())
                .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                .withDescription("Error getting group: " + e.getMessage())
                .asRuntimeException());
        }
    }
    
    @Override
    public void listUserGroups(ListUserGroupsRequest request, StreamObserver<ListUserGroupsResponse> responseObserver) {
        try {
            // Recupera o userId autenticado a partir do contexto gRPC
            String userId = AuthInterceptor.USER_ID.get();
            if (userId == null || userId.isBlank()) {
                responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("Missing authenticated user")
                    .asRuntimeException());
                return;
            }

            System.out.println("📋 Listing user groups for: " + userId);

            var groups = repository.getUserGroups(userId);

            ListUserGroupsResponse.Builder builder = ListUserGroupsResponse.newBuilder();
            for (var group : groups) {
                builder.addGroups(GroupInfo.newBuilder()
                    .setGroupId(group.getGroupId())
                    .setName(group.getName())
                    .setParticipantCount(group.getParticipantIds().size())
                    .setType(group.getType())
                    .setLastMessageTimestamp(group.getCreatedAt()) // fallback until we track last message
                    .build());
            }

            builder.setPagination(Pagination.newBuilder()
                .setReturned(groups.size())
                .setLimit(50)
                .setOffset(0)
                .setHasMore(false)
                .build());

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                .withDescription("Error listing groups: " + e.getMessage())
                .asRuntimeException());
        }
    }
    
    @Override
    public void addParticipant(AddParticipantRequest request, StreamObserver<AddParticipantResponse> responseObserver) {
        try {
            String groupId = request.getGroupId();
            String userId = request.getUserId();
            
            System.out.println("➕ Adding participant to group");
            System.out.println("   Group ID: " + groupId);
            System.out.println("   User ID: " + userId);
            
            // Add participant using repository
            repository.addParticipantToGroup(groupId, userId);
            
            AddParticipantResponse response = AddParticipantResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Participant added successfully")
                .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.NOT_FOUND
                .withDescription(e.getMessage())
                .asRuntimeException());
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                .withDescription("Error adding participant: " + e.getMessage())
                .asRuntimeException());
        }
    }
    
    @Override
    public void removeParticipant(RemoveParticipantRequest request, StreamObserver<RemoveParticipantResponse> responseObserver) {
        try {
            String groupId = request.getGroupId();
            String userId = request.getUserId();
            
            System.out.println("➖ Removing participant from group");
            System.out.println("   Group ID: " + groupId);
            System.out.println("   User ID: " + userId);
            
            // Remove participant using repository
            repository.removeParticipantFromGroup(groupId, userId);
            
            RemoveParticipantResponse response = RemoveParticipantResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Participant removed successfully")
                .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.NOT_FOUND
                .withDescription(e.getMessage())
                .asRuntimeException());
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                .withDescription("Error removing participant: " + e.getMessage())
                .asRuntimeException());
        }
    }
}
