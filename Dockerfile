FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY build/libs/*.jar app.jar

USER nobody

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
