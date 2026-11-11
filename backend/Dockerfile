# ---- build stage -------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Dependencies first, so this layer is cached until pom.xml changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
# The repo is edited on Windows: strip any CRLF and make the wrapper executable.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q -B dependency:go-offline

COPY src src
# Tests need Docker (Testcontainers), which is not available inside the Railway build. They run locally.
RUN ./mvnw -q -B package -DskipTests && cp target/*.jar /workspace/app.jar

# ---- runtime stage -----------------------------------------------------------
FROM eclipse-temurin:21-jre
RUN groupadd --system ledgerline && useradd --system --gid ledgerline --no-create-home ledgerline
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
USER ledgerline

# server.port reads PORT (Railway sets it); 8080 is the local default.
EXPOSE 8080
CMD ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
