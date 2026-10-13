FROM eclipse-temurin:21-jdk-alpine@sha256:0bfc69a4758a86710e5c474032d28400a8bd00874766f9e8b1642ac2fd293159
WORKDIR /opt/probe
COPY backend/platform-infrastructure/target/probe-libs/ /opt/probe/lib/
COPY backend/platform-app/target/probe-libs/ /opt/probe/lib/
COPY backend/platform-infrastructure/target/test-classes/ /opt/probe/target/test-classes/
COPY backend/platform-app/target/test-classes/ /opt/probe/target/test-classes/
COPY backend/platform-app/target/classes/ /opt/probe/classes/
RUN mkdir /reports && chown 10001:10001 /reports
USER 10001:10001
ENTRYPOINT ["java", "-Xmx256m", "-cp", "/opt/probe/lib/*:/opt/probe/classes:/opt/probe/target/test-classes", "org.junit.platform.console.ConsoleLauncher", "execute", "--select-package=com.agentplatform.infrastructure.harness.agentscope", "--select-class=com.agentplatform.app.HarnessComponentContractTest", "--select-class=com.agentplatform.app.ArchitectureTest", "--fail-if-no-tests", "--reports-dir=/reports"]
