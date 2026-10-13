FROM python:3.12-slim-bookworm@sha256:34386ef0cb081344d7ec1c103ba398e6e9f64e9ab3a1509accc92a4e24a07258
RUN pip install --no-cache-dir boto3==1.40.45 pika==1.3.2 psycopg[binary]==3.2.10
COPY deploy/probes/storage_probe.py /opt/probes/storage_probe.py
USER 10001:10001
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
ENTRYPOINT ["python", "/opt/probes/storage_probe.py"]
