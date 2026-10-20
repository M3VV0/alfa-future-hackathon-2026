FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY contracts/pom.xml contracts/pom.xml
COPY platform/pom.xml platform/pom.xml
COPY credit/pom.xml credit/pom.xml
COPY assessment/pom.xml assessment/pom.xml
COPY contracts/src contracts/src
COPY platform/src platform/src
COPY credit/src credit/src
COPY assessment/src assessment/src
COPY openapi openapi
RUN --mount=type=cache,target=/root/.m2 mvn -B -Dmaven.test.skip=true package
FROM eclipse-temurin:21.0.8_9-jre-jammy AS runtime
RUN groupadd -g 10001 app && useradd -u 10001 -g app -M app
WORKDIR /app
COPY --chmod=755 infra/java-healthcheck.sh /app/healthcheck
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
FROM runtime AS credit
COPY --from=build --chown=10001:10001 /workspace/credit/target/credit-0.1.0-SNAPSHOT.jar /app/app.jar
ENTRYPOINT ["java","-jar","/app/app.jar"]
FROM runtime AS assessment
COPY --from=build --chown=10001:10001 /workspace/assessment/target/assessment-0.1.0-SNAPSHOT.jar /app/app.jar
ENTRYPOINT ["java","-jar","/app/app.jar"]
