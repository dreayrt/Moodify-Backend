# Stage 1: Build JAR
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Copy Maven wrapper and POM
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B || true

# Copy source code and build
COPY src src
RUN ./mvnw clean package -DskipTests

# Stage 2: Minimal Production JRE
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create unprivileged user for security
RUN addgroup -S moodify && adduser -S moodify -G moodify
USER moodify

# Copy executable jar from builder stage
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8088
ENV SERVER_PORT=8088

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
