FROM eclipse-temurin:11-jre-alpine

WORKDIR /app

# Copy JAR from Maven build output
COPY target/websocket-gateway-1.0.0-SNAPSHOT.jar app.jar

# Expose WebSocket port and metrics port
EXPOSE 8085 9095

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:9095/metrics || exit 1

# Run
ENTRYPOINT ["java", "-jar", "app.jar"]
