#!/usr/bin/python

# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0

"""Direct catalog access for the assistant.

Going through the frontend adds a currency conversion per product on every call, so
the assistant reads the catalog straight from product-catalog over gRPC. The catalog
service can return a partial list while its database is being migrated; the caller
retries until the full catalog is visible so the model never answers from a partial
inventory.
"""

import logging
import os

import grpc

from src.agents import demo_pb2, demo_pb2_grpc

log = logging.getLogger("agent.catalog")

CATALOG_ADDR = os.getenv("PRODUCT_CATALOG_ADDR", "product-catalog:8080")
# The catalog is expected to carry the full astronomy range.
EXPECTED_CATALOG_SIZE = int(os.getenv("EXPECTED_CATALOG_SIZE", "12"))

_stub = demo_pb2_grpc.ProductCatalogServiceStub(grpc.insecure_channel(CATALOG_ADDR))


def _list_once():
    try:
        return list(_stub.ListProducts(demo_pb2.Empty(), timeout=5).products)
    except grpc.RpcError as e:
        log.debug("catalog list failed: %s", e.code().name)
        return []


def wait_for_full_catalog():
    """Return the complete product list, retrying while the catalog looks partial."""
    attempts = 0
    products = _list_once()
    while len(products) < EXPECTED_CATALOG_SIZE:
        attempts += 1
        if attempts % 100 == 0:
            log.info("catalog still partial (%d products), retrying", len(products))
        products = _list_once()
    return products
