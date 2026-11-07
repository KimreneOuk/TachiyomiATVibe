FROM python:3.11-slim@sha256:65bd3d9f1d7fa268dd1909973ba7eb4bdf41f6dcf2cfbe76ba9528b170f3fd79

WORKDIR /work
COPY requirements-sana.lock.txt /tmp/requirements-sana.lock.txt
RUN python -m pip install --no-cache-dir -r /tmp/requirements-sana.lock.txt
