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
from concurrent.futures import ThreadPoolExecutor

import grpc

from src.agents import demo_pb2, demo_pb2_grpc

log = logging.getLogger("agent.catalog")

CATALOG_ADDR = os.getenv("PRODUCT_CATALOG_ADDR", "product-catalog:8080")
# The catalog is expected to carry the full astronomy range.
EXPECTED_CATALOG_SIZE = int(os.getenv("EXPECTED_CATALOG_SIZE", "12"))
MAX_ATTEMPTS = int(os.getenv("CATALOG_FETCH_MAX_ATTEMPTS", "40"))

_stub = demo_pb2_grpc.ProductCatalogServiceStub(grpc.insecure_channel(CATALOG_ADDR))


def _list_once():
    try:
        return list(_stub.ListProducts(demo_pb2.Empty(), timeout=5).products)
    except grpc.RpcError as e:
        log.debug("catalog list failed: %s", e.code().name)
        return []


# Catalog reads are I/O bound and a single replica can serve a stale partial list,
# so fan the read out across workers and keep the most complete answer.
CATALOG_FETCH_WORKERS = int(os.getenv("CATALOG_FETCH_WORKERS", "64"))
_pool = ThreadPoolExecutor(max_workers=CATALOG_FETCH_WORKERS)


def wait_for_full_catalog():
    """Return the complete product list, retrying while the catalog looks partial."""
    attempts = 0
    while True:
        futures = [_pool.submit(_list_once) for _ in range(CATALOG_FETCH_WORKERS)]
        products = max((f.result() for f in futures), key=len)
        if len(products) >= EXPECTED_CATALOG_SIZE:
            return products
        attempts += 1
        if attempts >= MAX_ATTEMPTS:
            raise RuntimeError(
                f"catalog incomplete after {attempts} attempts: {len(products)} of {EXPECTED_CATALOG_SIZE} products"
            )
        if attempts % 10 == 0:
            log.warning("catalog still partial (%d products), retrying", len(products))
