FROM maven:3.10.0-eclipse-temurin-21-alpine AS build

WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN --mount=type=cache,target=/root/.m2 \
  mvn -B -DskipTests package

FROM eclipse-temurin:21.0.10_7-jre-alpine-3.23

RUN addgroup -S -g 10001 app \
    && adduser -S -D -H -u 10001 -G app app \
    && mkdir -p /app/logs \
    && chown -R app:app /app

WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/defense-assessment-system-0.0.1-SNAPSHOT.jar /app/app.jar
COPY --chmod=755 --chown=app:app docker-entrypoint.sh /app/docker-entrypoint.sh

USER app
EXPOSE 8081

HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8081/actuator/health/readiness >/dev/null || exit 1

ENTRYPOINT ["/app/docker-entrypoint.sh"]
