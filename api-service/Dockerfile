# ============================================================================
# Dockerfile for API Service (gRPC)
# ============================================================================
# Simplified: Uses pre-compiled JAR (build with `mvn package` first)
# ============================================================================

FROM eclipse-temurin:17-jre-alpine

# Add labels
LABEL maintainer="Chat4All Team"
LABEL description="API Service - gRPC endpoints for Chat4All platform"

# Create non-root user
RUN addgroup -S chat4all && adduser -S chat4all -G chat4all

# Set working directory
WORKDIR /app

# Copy pre-compiled JAR from Maven build (context is project root)
COPY ./api-service/target/api-service-1.0.0-SNAPSHOT.jar app.jar

# Change ownership
RUN chown -R chat4all:chat4all /app

# Switch to non-root user
USER chat4all

# Expose gRPC port
EXPOSE 9090

# Health check using TCP
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s \
  CMD nc -z localhost 9090 || exit 1

# Run application
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
