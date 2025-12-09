# ============================================================================
# WhatsApp Connector Mock - Dockerfile
# ============================================================================
# Purpose: Package connector as lightweight Docker image
# Base Image: Eclipse Temurin JRE 17 Alpine (minimal footprint)
# Build Strategy: Multi-stage build (Maven → Runtime)
# ============================================================================

# ============================================================================
# STAGE 1: BUILD
# ============================================================================
FROM maven:3.9-eclipse-temurin-17-alpine AS build

WORKDIR /app

# Copy parent POM and shared module first (cache optimization)
COPY pom.xml .
COPY shared/ shared/

# Copy connector source
COPY connector-whatsapp/ connector-whatsapp/

# Copy other module POMs (needed for reactor build)
COPY api-service/pom.xml api-service/pom.xml
COPY router-worker/pom.xml router-worker/pom.xml
COPY connector-instagram/pom.xml connector-instagram/pom.xml
COPY websocket-gateway/pom.xml websocket-gateway/pom.xml

# Build only connector-whatsapp module
RUN mvn clean package -DskipTests -pl connector-whatsapp -am

# ============================================================================
# STAGE 2: RUNTIME
# ============================================================================
FROM eclipse-temurin:17-jre-alpine

# Create non-root user for security
RUN addgroup -S chat4all && adduser -S chat4all -G chat4all

WORKDIR /app

# Copy JAR from build stage
COPY --from=build /app/connector-whatsapp/target/connector-whatsapp-1.0.0-SNAPSHOT.jar app.jar

# Change ownership
RUN chown -R chat4all:chat4all /app

# Switch to non-root user
USER chat4all

# Expose health check port
EXPOSE 8083

# JVM options for container environment
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Run connector
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
