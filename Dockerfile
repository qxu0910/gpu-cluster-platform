FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21.0.8_9-jre
WORKDIR /app
COPY --from=build /build/target/gpu-cluster-platform-0.1.0.jar app.jar
USER 10001:10001
ENTRYPOINT ["java","-jar","app.jar"]
