# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0

FROM docker.io/library/python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app

COPY ./src/catalog-satellite/requirements.txt requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY ./src/catalog-satellite/demo_pb2.py demo_pb2.py
COPY ./src/catalog-satellite/demo_pb2_grpc.py demo_pb2_grpc.py
COPY ./src/catalog-satellite/app.py app.py

EXPOSE 8080
ENTRYPOINT ["opentelemetry-instrument", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8080", "--log-level", "warning"]
