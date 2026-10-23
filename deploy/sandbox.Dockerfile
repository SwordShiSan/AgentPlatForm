FROM node:22.23.3-trixie-slim@sha256:6b25e0bf3aaee33c18ba3c5d1b71646df1ba485a2a5500fd8376a3be3d110812 AS node
FROM python:3.12.15-slim-trixie@sha256:2b4f19dae3a777dfc3b76730bda1e82e1f66ab2a2686fa93ca78edbfb4f04ffe
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY deploy/scripts/sandbox-entrypoint.py /opt/platform/sandbox-entrypoint.py
COPY deploy/scripts/sandbox-resource-probe.py /opt/platform/sandbox-resource-probe.py
WORKDIR /workspace
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
USER 10001:10001
ENTRYPOINT ["python3", "/opt/platform/sandbox-entrypoint.py"]
