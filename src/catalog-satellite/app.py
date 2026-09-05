# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0

"""A small service that depends on the product catalog.

The same image runs as several differently named services (inventory-sync,
price-monitor, and so on). Each exposes /sync, which reads the catalog over gRPC
and, depending on ROLE, also asks currency or recommendation. A failing catalog
therefore fails every one of them, which is the point: it widens the blast radius
of a catalog defect so a problem touches the whole shop, not one page.
"""

import logging
import os
import random

import grpc
from fastapi import FastAPI, HTTPException

import demo_pb2
import demo_pb2_grpc

log = logging.getLogger("catalog-satellite")
logging.basicConfig(level=logging.INFO)

ROLE = os.getenv("ROLE", "inventory")
CATALOG_ADDR = os.getenv("PRODUCT_CATALOG_ADDR", "product-catalog:8080")
CURRENCY_ADDR = os.getenv("CURRENCY_ADDR", "currency:8080")
RECOMMENDATION_ADDR = os.getenv("RECOMMENDATION_ADDR", "recommendation:8080")
TIMEOUT = float(os.getenv("RPC_TIMEOUT_SECONDS", "5"))

catalog = demo_pb2_grpc.ProductCatalogServiceStub(grpc.insecure_channel(CATALOG_ADDR))
currency = demo_pb2_grpc.CurrencyServiceStub(grpc.insecure_channel(CURRENCY_ADDR))
recommendation = demo_pb2_grpc.RecommendationServiceStub(grpc.insecure_channel(RECOMMENDATION_ADDR))

app = FastAPI(title=f"catalog satellite ({ROLE})")


def _fail(step: str, err: grpc.RpcError):
    log.error("%s failed during %s: %s %s", ROLE, step, err.code().name, err.details())
    raise HTTPException(status_code=502, detail=f"{step} failed: {err.code().name}: {err.details()}")


@app.get("/healthz")
def healthz():
    return {"ok": True, "role": ROLE}


@app.get("/sync")
def sync():
    """One unit of this service's work: refresh its view of the catalog."""
    try:
        products = catalog.ListProducts(demo_pb2.Empty(), timeout=TIMEOUT).products
    except grpc.RpcError as e:
        _fail("catalog list", e)
    if not products:
        raise HTTPException(status_code=502, detail="catalog returned no products")

    sample = random.choice(products)
    try:
        product = catalog.GetProduct(demo_pb2.GetProductRequest(id=sample.id), timeout=TIMEOUT)
    except grpc.RpcError as e:
        _fail("catalog get", e)

    extra = {}
    if ROLE in ("price-monitor", "promo-engine"):
        try:
            converted = currency.Convert(
                demo_pb2.CurrencyConversionRequest(**{"from": product.price_usd, "to_code": "EUR"}),
                timeout=TIMEOUT,
            )
            extra["eur"] = f"{converted.units}.{converted.nanos // 10_000_000:02d}"
        except grpc.RpcError as e:
            _fail("currency convert", e)
    if ROLE in ("search-indexer", "wishlist"):
        try:
            recs = recommendation.ListRecommendations(
                demo_pb2.ListRecommendationsRequest(user_id=ROLE, product_ids=[product.id]),
                timeout=TIMEOUT,
            )
            extra["recommendations"] = list(recs.product_ids)
        except grpc.RpcError as e:
            _fail("recommendations", e)

    return {"role": ROLE, "products": len(products), "checked": product.id, **extra}
