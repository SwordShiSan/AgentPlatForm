FROM python:3.12.15-slim-trixie@sha256:2b4f19dae3a777dfc3b76730bda1e82e1f66ab2a2686fa93ca78edbfb4f04ffe
ARG SOURCE_REVISION=unspecified
LABEL org.opencontainers.image.revision=$SOURCE_REVISION
RUN sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get -o Acquire::Retries=3 update && apt-get install -y --no-install-recommends xfsprogs=6.13.0-2+deb13u1 util-linux=2.41.5-0+deb13u1 \
    && dpkg-query -W -f='${Package}=${Version}\n' > /opt/native-packages.lock \
    && rm -rf /var/lib/apt/lists/*
COPY deploy/scripts/workspace-quota-helper.py /opt/workspace-quota-helper.py
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
USER 0:0
ENTRYPOINT ["python3", "/opt/workspace-quota-helper.py"]
