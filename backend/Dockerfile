FROM eclipse-temurin:25-jdk-alpine AS jre

RUN apk add --no-cache binutils && \
    jlink \
      --add-modules java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,java.xml,jdk.crypto.ec,jdk.net,jdk.unsupported \
      --strip-debug \
      --no-header-files \
      --no-man-pages \
      --compress=zip-9 \
      --output /opt/jre

FROM eclipse-temurin:25-jdk-alpine AS build

WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw dependency:go-offline -B

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 ./mvnw package -DskipTests -B && \
    java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination /app/extracted && \
    mkdir -p /app/extracted/snapshot-dependencies

FROM alpine:3.21

RUN apk add --no-cache ca-certificates tzdata && \
    addgroup -S appgroup && adduser -S appuser -G appgroup

ENV JAVA_HOME=/opt/jre
ENV PATH="$JAVA_HOME/bin:$PATH"

WORKDIR /app

COPY --from=jre /opt/jre /opt/jre
COPY --from=build --chown=appuser:appgroup /app/extracted/dependencies/ ./
COPY --from=build --chown=appuser:appgroup /app/extracted/spring-boot-loader/ ./
COPY --from=build --chown=appuser:appgroup /app/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=appuser:appgroup /app/extracted/application/ ./

USER appuser:appgroup

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-XX:+UseStringDeduplication", "org.springframework.boot.loader.launch.JarLauncher"]
