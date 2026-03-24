# ── Stage 1: Build the fat JAR ────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21 AS build

# Set working directory inside the container
WORKDIR /app

# Copy pom.xml first — lets Docker cache dependencies
# if only source code changes, Maven does not re-download
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source code
COPY src ./src

# Build the fat JAR — skipping tests for faster deployment
RUN mvn package -DskipTests -q

# ── Stage 2: Run the fat JAR ──────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy only the fat JAR from the build stage
# This keeps the final image small — no Maven, no source code
COPY --from=build /app/target/notepad-app-1.0.0-SNAPSHOT-fat.jar app.jar

# Railway assigns a PORT environment variable dynamically
# Expose it so Railway knows which port to route traffic to
EXPOSE 8080

# Start the application
ENTRYPOINT ["java", "-jar", "app.jar"]
