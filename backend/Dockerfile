FROM eclipse-temurin:25-jdk-alpine AS build

RUN apk add --no-cache binutils

WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw dependency:go-offline -B

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 ./mvnw package -DskipTests -B

RUN mkdir -p /app/extracted/snapshot-dependencies && \
    java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination /app/extracted && \
    ( \
      echo "java.base"; \
      echo "java.instrument"; \
      echo "jdk.crypto.ec"; \
      for item in /app/extracted/spring-boot-loader /app/extracted/application/BOOT-INF/classes /app/extracted/dependencies/BOOT-INF/lib/*.jar; do \
        jdeps --ignore-missing-deps -q --recursive --print-module-deps \
          --class-path "/app/extracted/dependencies/BOOT-INF/lib/*" "$item" 2>/dev/null || \
        jdeps --ignore-missing-deps -q --recursive --multi-release 25 --print-module-deps \
          --class-path "/app/extracted/dependencies/BOOT-INF/lib/*" "$item" 2>/dev/null || true; \
      done | tr ',' '\n'; \
    ) | grep -v '^$' | sort -u | tr '\n' ',' | sed 's/,$//' > /tmp/modules.txt && \
    jlink \
      --add-modules "$(cat /tmp/modules.txt)" \
      --strip-debug \
      --no-header-files \
      --no-man-pages \
      --compress=zip-6 \
      --output /opt/jre

FROM alpine:3.21

RUN apk add --no-cache ca-certificates tzdata && \
    addgroup -S appgroup && adduser -S appuser -G appgroup

ENV JAVA_HOME=/opt/jre
ENV PATH="$JAVA_HOME/bin:$PATH"

WORKDIR /app

COPY --from=build /opt/jre /opt/jre
COPY --from=build --chown=appuser:appgroup /app/extracted/dependencies/ ./
COPY --from=build --chown=appuser:appgroup /app/extracted/spring-boot-loader/ ./
COPY --from=build --chown=appuser:appgroup /app/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=appuser:appgroup /app/extracted/application/ ./

USER appuser:appgroup

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-XX:+UseStringDeduplication", "org.springframework.boot.loader.launch.JarLauncher"]
