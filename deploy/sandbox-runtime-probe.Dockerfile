FROM eclipse-temurin:21.0.12.1_1-jdk-noble@sha256:743b453cb8639d815a8251e128f7d8e88840c684e43fdbde1b1e79788437b5cf
WORKDIR /opt/probe
COPY backend/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar /opt/probe/platform.jar
COPY deploy/probes/SandboxRuntimeProbe.java /opt/probe/SandboxRuntimeProbe.java
RUN jar --extract --file platform.jar BOOT-INF/lib \
    && javac --release 21 -cp 'BOOT-INF/lib/*' SandboxRuntimeProbe.java \
    && rm platform.jar SandboxRuntimeProbe.java
USER 10001:10001
ENTRYPOINT ["java","-Xms16m","-Xmx128m","-cp","/opt/probe:/opt/probe/BOOT-INF/lib/*","SandboxRuntimeProbe"]
