# syntax=docker/dockerfile:1.7
FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml .
COPY services/booking-service/pom.xml services/booking-service/pom.xml
COPY services/payment-service/pom.xml services/payment-service/pom.xml
COPY services/booking-service services/booking-service

RUN --mount=type=cache,target=/root/.m2 \
    mvn -pl services/booking-service -am package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN addgroup --system --gid 10001 spring \
    && adduser --system --uid 10001 --ingroup spring spring
USER 10001:10001

COPY --from=build /workspace/services/booking-service/target/booking-service-*.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
