# Stage 1: Build stage
FROM maven:3.9.6-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Kopiowanie pliku pom.xml i pobranie zależności (dzięki temu Docker cache'uje zależności jeśli pom.xml się nie zmienił)
COPY pom.xml .
RUN mvn dependency:go-offline

# Kopiowanie kodu źródłowego i budowanie aplikacji
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Kopiowanie skompilowanego pliku jar z poprzedniego etapu
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8084

ENTRYPOINT ["java", "-jar", "app.jar"]
