FROM maven:3.9.13-eclipse-temurin-17
WORKDIR /app
COPY pom.xml .
COPY contracts contracts
COPY pricing-grpc pricing-grpc
COPY mobility-observability mobility-observability
COPY operator-journal operator-journal
COPY autopartage/pom.xml autopartage/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY search-catalog search-catalog
COPY search-bff/pom.xml search-bff/pom.xml
COPY tarification/pom.xml tarification/pom.xml
COPY reservation/pom.xml reservation/pom.xml
COPY result-events/pom.xml result-events/pom.xml
COPY notification/pom.xml notification/pom.xml
COPY rail-bridge rail-bridge
COPY air-bridge air-bridge
COPY starter-kit-mobilite starter-kit-mobilite
RUN mvn -B -ntp -pl rail-bridge,air-bridge -am install
