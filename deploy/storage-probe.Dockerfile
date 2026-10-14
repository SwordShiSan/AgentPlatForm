FROM python:3.12-slim-bookworm@sha256:34386ef0cb081344d7ec1c103ba398e6e9f64e9ab3a1509accc92a4e24a07258
COPY deploy/probes/requirements.lock /opt/probes/requirements.lock
RUN pip install --no-cache-dir --require-hashes -r /opt/probes/requirements.lock
COPY deploy/probes/storage_probe.py /opt/probes/storage_probe.py
USER 10001:10001
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
ENTRYPOINT ["python", "/opt/probes/storage_probe.py"]
