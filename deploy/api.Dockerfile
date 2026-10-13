FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555
WORKDIR /app
COPY --chown=10001:10001 backend/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar /app/platform.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-Xms64m", "-Xmx192m", "-jar", "/app/platform.jar"]
