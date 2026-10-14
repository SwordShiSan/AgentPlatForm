FROM eclipse-temurin:21.0.12.1_1-jdk-noble@sha256:743b453cb8639d815a8251e128f7d8e88840c684e43fdbde1b1e79788437b5cf
WORKDIR /opt/probe
COPY backend/platform-infrastructure/target/probe-libs/ /opt/probe/lib/
COPY backend/platform-app/target/probe-libs/ /opt/probe/lib/
COPY backend/platform-infrastructure/target/test-classes/ /opt/probe/target/test-classes/
COPY backend/platform-app/target/test-classes/ /opt/probe/target/test-classes/
COPY backend/platform-app/target/classes/ /opt/probe/classes/
RUN mkdir /reports && chown 10001:10001 /reports
USER 10001:10001
ENTRYPOINT ["java", "-Xmx256m", "-cp", "/opt/probe/lib/*:/opt/probe/classes:/opt/probe/target/test-classes", "org.junit.platform.console.ConsoleLauncher", "execute", "--select-package=com.agentplatform.infrastructure.harness.agentscope", "--select-class=com.agentplatform.app.HarnessComponentContractTest", "--select-class=com.agentplatform.app.ArchitectureTest", "--select-class=com.agentplatform.app.SandboxAdmissionTest", "--fail-if-no-tests", "--reports-dir=/reports"]
