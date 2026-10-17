# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

# Run stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/monetra-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8081
# A small server (for example Render's free plan, 512 MB): use about two thirds of the memory for Java
# and the light garbage collector. Tested to run in a 330 MB heap.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-XX:+UseSerialGC", "-jar", "app.jar"]
