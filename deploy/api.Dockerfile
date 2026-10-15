FROM eclipse-temurin:21.0.12.1_1-jdk-noble@sha256:743b453cb8639d815a8251e128f7d8e88840c684e43fdbde1b1e79788437b5cf AS health-build
COPY deploy/probes/HttpHealthcheck.java /tmp/HttpHealthcheck.java
RUN mkdir /health && javac --release 21 -d /health /tmp/HttpHealthcheck.java
FROM eclipse-temurin:21.0.12.1_1-jre-noble@sha256:7fd597bf48c8bb13a7a3fb227f8366dec0423f78775d4b4f7e30409204af8627
WORKDIR /app
COPY --chown=10001:10001 backend/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar /app/platform.jar
COPY --from=health-build --chown=10001:10001 /health/ /app/health/
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-Xms64m", "-Xmx192m", "-jar", "/app/platform.jar"]
