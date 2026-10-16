FROM python:3.12.15-slim-trixie@sha256:2b4f19dae3a777dfc3b76730bda1e82e1f66ab2a2686fa93ca78edbfb4f04ffe
ARG SOURCE_REVISION=unspecified
LABEL org.opencontainers.image.revision=$SOURCE_REVISION
COPY deploy/probes/chat_provider_fixture.py /app/fixture.py
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
USER 10001:10001
ENTRYPOINT ["python", "/app/fixture.py"]
