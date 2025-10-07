# Multi-stage Dockerfile for Data Forge Middleware
# Optimized for production deployment with minimal image size

# ============================================
# Stage 1: Build Stage
# ============================================
FROM gradle:8.5-jdk21-alpine AS builder

# Set working directory
WORKDIR /app

# Copy Gradle wrapper and build files first (for layer caching)
COPY gradle gradle/
COPY gradlew .
COPY build.gradle.kts .
COPY settings.gradle.kts .

# Download dependencies (cached layer)
RUN ./gradlew dependencies --no-daemon

# Copy source code
COPY src src/

# Build the application
RUN ./gradlew clean bootJar --no-daemon -x test

# Verify the JAR was created
RUN ls -lh build/libs/

# ============================================
# Stage 2: Runtime Stage (Production)
# ============================================
FROM eclipse-temurin:21-jre-alpine AS production

# Install curl for health checks
RUN apk add --no-cache curl

# Create non-root user for security
RUN addgroup -S dfm && adduser -S dfm -G dfm

# Set working directory
WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /app/build/libs/*.jar app.jar

# Change ownership to non-root user
RUN chown -R dfm:dfm /app

# Switch to non-root user
USER dfm

# Expose application port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# JVM optimization flags for production
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:InitialRAMPercentage=50.0 \
               -XX:+UseG1GC \
               -XX:MaxGCPauseMillis=200 \
               -XX:+UseStringDeduplication \
               -XX:+OptimizeStringConcat \
               -Djava.security.egd=file:/dev/./urandom \
               -Dspring.backgroundpreinitializer.ignore=true"

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

# ============================================
# Stage 3: Development Stage (Optional)
# ============================================
FROM eclipse-temurin:21-jdk-alpine AS development

# Install curl and bash for debugging
RUN apk add --no-cache curl bash

# Create non-root user
RUN addgroup -S dfm && adduser -S dfm -G dfm

WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /app/build/libs/*.jar app.jar

RUN chown -R dfm:dfm /app

USER dfm

EXPOSE 8080 5005

# Enable remote debugging on port 5005
ENV JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
               -XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
