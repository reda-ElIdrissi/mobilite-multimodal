FROM maven:3.9.13-eclipse-temurin-17 AS agent
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp org.apache.maven.plugins:maven-dependency-plugin:3.9.0:copy \
    -Dartifact=io.opentelemetry.javaagent:opentelemetry-javaagent:2.17.1 -DoutputDirectory=/agent

FROM maven:3.9.13-eclipse-temurin-17 AS build
ARG MODULE
WORKDIR /app
COPY pom.xml .
COPY contracts contracts
COPY pricing-grpc pricing-grpc
COPY mobility-observability mobility-observability
COPY operator-journal operator-journal
COPY autopartage autopartage
COPY api-gateway api-gateway
COPY rail-bridge rail-bridge
COPY air-bridge air-bridge
COPY search-catalog search-catalog
COPY search-bff search-bff
COPY tarification tarification
COPY reservation reservation
COPY result-events result-events
COPY notification notification
COPY starter-kit-mobilite starter-kit-mobilite
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -pl "$MODULE" -am -DskipTests package \
 && cp "$MODULE/target/$MODULE-0.0.1-SNAPSHOT.jar" /app/service.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/service.jar service.jar
COPY --from=agent /agent/opentelemetry-javaagent-2.17.1.jar /app/opentelemetry-javaagent.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "service.jar"]
