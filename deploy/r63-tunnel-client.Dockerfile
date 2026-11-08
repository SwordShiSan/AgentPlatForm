FROM python:3.12.15-slim-trixie@sha256:2b4f19dae3a777dfc3b76730bda1e82e1f66ab2a2686fa93ca78edbfb4f04ffe
ARG SOURCE_REVISION=unspecified
ARG SOURCE_HASH=unspecified
LABEL org.opencontainers.image.revision=$SOURCE_REVISION \
      io.agentplatform.r63.tunnel-source=$SOURCE_HASH
RUN apt-get update \
    && apt-get install -y --no-install-recommends openssh-client passwd \
    && useradd --uid 10001 --no-create-home --home-dir /tmp --shell /usr/sbin/nologin r63tunnel \
    && rm -rf /var/lib/apt/lists/*
ENV HOME=/tmp
USER 10001:10001
ENTRYPOINT ["ssh"]
