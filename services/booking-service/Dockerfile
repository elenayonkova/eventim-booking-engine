FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml .
COPY services/booking-service/pom.xml services/booking-service/pom.xml
COPY services/payment-service/pom.xml services/payment-service/pom.xml
COPY services/booking-service services/booking-service

RUN mvn -pl services/booking-service -am package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app

RUN addgroup --system spring && adduser --system spring --ingroup spring
USER spring:spring

COPY --from=build /workspace/services/booking-service/target/booking-service-*.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
