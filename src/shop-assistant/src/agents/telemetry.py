#!/usr/bin/python

# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0

"""Metrics and logs export for the agent.

Traceloop owns the global tracer. Metrics and logs get their own providers here,
deliberately not registered globally, so nothing fights over the SDK globals.
"""

import logging
import os

from opentelemetry.exporter.otlp.proto.http._log_exporter import OTLPLogExporter
from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
from opentelemetry.sdk._logs import LoggerProvider, LoggingHandler
from opentelemetry.sdk._logs.export import BatchLogRecordProcessor
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource


def _base_url():
    return (
        os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
        or os.getenv("TRACELOOP_BASE_URL")
        or "http://localhost:4318"
    ).rstrip("/")


def _resource():
    return Resource.create({"service.name": os.getenv("OTEL_SERVICE_NAME", "agent")})


class AgentTelemetry:
    def __init__(self):
        base = _base_url()
        self.meter_provider = MeterProvider(
            resource=_resource(),
            metric_readers=[
                PeriodicExportingMetricReader(
                    OTLPMetricExporter(endpoint=f"{base}/v1/metrics"),
                    export_interval_millis=15000,
                )
            ],
        )
        meter = self.meter_provider.get_meter("astroshop.agent")
        self.groundedness = meter.create_histogram(
            "astroshop.agent.groundedness",
            unit="1",
            description="1 when the answer names a real catalog product, 0 when it does not",
        )
        self.hallucinations = meter.create_counter(
            "astroshop.agent.hallucinations",
            unit="1",
            description="Product answers that referenced no real catalog product",
        )
        self.requests = meter.create_counter(
            "astroshop.agent.requests", unit="1", description="Prompts handled"
        )

        self.logger_provider = LoggerProvider(resource=_resource())
        self.logger_provider.add_log_record_processor(
            BatchLogRecordProcessor(OTLPLogExporter(endpoint=f"{base}/v1/logs"))
        )
        handler = LoggingHandler(level=logging.INFO, logger_provider=self.logger_provider)
        logging.getLogger().addHandler(handler)

    def shutdown(self):
        self.meter_provider.shutdown()
        self.logger_provider.shutdown()
