FROM python:3.12.15-slim-trixie@sha256:2b4f19dae3a777dfc3b76730bda1e82e1f66ab2a2686fa93ca78edbfb4f04ffe
ARG SOURCE_REVISION=unspecified
LABEL org.opencontainers.image.revision=$SOURCE_REVISION
COPY deploy/scripts/file-stream-bridge.py /opt/platform/file-stream-bridge.py
COPY deploy/scripts/file-scan-bridge.py /opt/platform/file-scan-bridge.py
COPY deploy/scripts/sandbox-file-bridge.py /opt/platform/sandbox-file-bridge.py
COPY deploy/scripts/file-helper-entrypoint.py /opt/platform/file-helper-entrypoint.py
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /workspace
USER 10001:10001
ENTRYPOINT ["python3", "/opt/platform/file-helper-entrypoint.py", "idle"]
