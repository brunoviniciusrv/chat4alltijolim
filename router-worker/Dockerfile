# ============================================================================
# Dockerfile for Router Worker
# ============================================================================
# Purpose: Containerize Kafka consumer / Cassandra writer service
# Base image: OpenJDK 17 slim (minimal footprint)
# ============================================================================

FROM maven:3.9-eclipse-temurin-17-alpine AS build

# Set working directory
WORKDIR /app

# Copy all source code (POMs + source)
COPY pom.xml .
COPY shared/ shared/
COPY router-worker/ router-worker/
COPY api-service/pom.xml api-service/pom.xml
COPY connector-whatsapp/pom.xml connector-whatsapp/pom.xml
COPY connector-instagram/pom.xml connector-instagram/pom.xml
COPY websocket-gateway/pom.xml websocket-gateway/pom.xml

# Build application (skip tests in Docker build - run separately)
RUN mvn clean package -DskipTests -pl router-worker -am

# ============================================================================
# Runtime stage (smaller image)
COPY websocket-gateway/pom.xml websocket-gateway/pom.xml
# ============================================================================
FROM eclipse-temurin:17-jre-alpine

# Add labels for documentation
LABEL maintainer="Chat4All Team"
LABEL description="Router Worker - Kafka consumer and message processor"
LABEL educational="Demonstrates event-driven worker pattern"

# Create non-root user for security
RUN addgroup -S chat4all && adduser -S chat4all -G chat4all

# Set working directory
WORKDIR /app

# Copy JAR from build stage
COPY --from=build /app/router-worker/target/router-worker-1.0.0-SNAPSHOT.jar app.jar

# Change ownership
RUN chown -R chat4all:chat4all /app

# Switch to non-root user
USER chat4all

# No ports exposed (worker doesn't accept HTTP requests)
# It only consumes from Kafka and writes to Cassandra

# Run application
# Educational note: Long-running consumer loop, not a web server
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
