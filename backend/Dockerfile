# Stage 1: Build the Spring Boot application using Gradle
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace/app

# Copy gradle wrapper and definition files
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
COPY gradle.properties .

# Give execute permissions to Gradle wrapper
RUN chmod +x ./gradlew

# Download dependencies (caching layer)
RUN ./gradlew dependencies --no-daemon || true

# Copy source code and compile
COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# Stage 2: Minimal Production JRE Image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copy built jar from builder stage
COPY --from=builder /workspace/app/build/libs/*.jar app.jar

# Environment defaults (H2 embedded in PostgreSQL compatibility mode)
ENV PORT=8080
ENV SPRING_PROFILES_ACTIVE=prod
ENV SPRING_DATASOURCE_URL=jdbc:h2:file:/app/data/neurovault-db;DB_CLOSE_DELAY=-1;MODE=PostgreSQL
ENV SPRING_DATASOURCE_DRIVER=org.h2.Driver
ENV SPRING_DATASOURCE_USERNAME=sa
ENV SPRING_DATASOURCE_PASSWORD=

EXPOSE 8080

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
