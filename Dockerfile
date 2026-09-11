FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
COPY contracts ./contracts
COPY src ./src

# The authoritative verification gate runs before the image build because the
# PostgreSQL integration tests use Testcontainers and a standard Docker build
# must not receive the host Docker socket. Test sources are still compiled here.
RUN mvn -B --no-transfer-progress -DskipTests clean package

FROM eclipse-temurin:17-jre-alpine

# The runtime base currently ships OpenSSL 3.5.7-r0. Refresh only the runtime
# packages to Alpine's fixed CVE-2026-14456 build.
RUN apk add --no-cache --upgrade \
    libcrypto3=3.5.8-r0 \
    libssl3=3.5.8-r0 \
    expat=2.8.4-r0 \
    openssl=3.5.8-r0
WORKDIR /app

COPY --from=build /app/target/application-tracker-service-1.0.0.jar app.jar

RUN apk add --no-cache curl
EXPOSE 8088
ENTRYPOINT ["java", "-jar", "app.jar"]
