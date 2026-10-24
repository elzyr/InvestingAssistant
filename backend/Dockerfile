FROM eclipse-temurin:25-jdk-alpine AS build
RUN apk add --no-cache maven
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B || true
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER appuser:appgroup
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-XX:+UseStringDeduplication", "-jar", "app.jar"]
