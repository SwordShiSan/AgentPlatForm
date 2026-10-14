FROM python:3.12.15-slim-trixie@sha256:2b4f19dae3a777dfc3b76730bda1e82e1f66ab2a2686fa93ca78edbfb4f04ffe
COPY deploy/probes/requirements.lock /opt/probes/requirements.lock
RUN pip install --no-cache-dir --require-hashes -r /opt/probes/requirements.lock && pip check
COPY deploy/probes/storage_probe.py /opt/probes/storage_probe.py
COPY deploy/probes/sts_denial.py /opt/probes/sts_denial.py
USER 10001:10001
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
ENTRYPOINT ["python", "/opt/probes/storage_probe.py"]
