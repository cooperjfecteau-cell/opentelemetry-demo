#!/usr/bin/python

# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0

"""Feature-flag lookup and a deterministic groundedness check for the agent.

The flag is read over flagd's OFREP HTTP API so the agent needs no gRPC client.
Groundedness compares the answer against the live catalog: an answer that talks
about products but names none that exist scores 0. That is cheap, exact, and
enough for an anomaly detector to notice when the agent starts inventing gear.
"""

import logging
import os
import re
import time

import httpx

log = logging.getLogger("agent.grounding")

FLAGD_URL = f"http://{os.getenv('FLAGD_HOST', 'flagd')}:{os.getenv('FLAGD_OFREP_PORT', '8016')}"
CATALOG_URL = f"http://{os.getenv('APPLICATION_ENDPOINT', 'localhost:8080')}/api/products"
CATALOG_TTL_SECONDS = 300

# Words that mark an answer as being about products at all. Answers with none of
# these (currency questions, greetings) are not scored.
PRODUCT_WORDS = re.compile(
    r"\b(telescope|binocular|lens|flashlight|filter|imager|book|kit|tube|assembly|product|price)\b|\$",
    re.IGNORECASE,
)

_catalog_cache = {"expires": 0.0, "names": [], "ids": []}


async def flag_enabled(flag: str, default: bool = True) -> bool:
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(2.0)) as client:
            res = await client.post(
                f"{FLAGD_URL}/ofrep/v1/evaluate/flags/{flag}", json={"context": {}}
            )
            res.raise_for_status()
            return bool(res.json().get("value", default))
    except Exception as e:
        log.warning("flag %s unavailable, using default %s: %s", flag, default, e)
        return default


async def _catalog():
    now = time.monotonic()
    if now < _catalog_cache["expires"] and _catalog_cache["names"]:
        return _catalog_cache["names"], _catalog_cache["ids"]
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(5.0)) as client:
            res = await client.get(CATALOG_URL)
            res.raise_for_status()
            products = res.json()
        _catalog_cache["names"] = [p["name"].lower() for p in products if p.get("name")]
        _catalog_cache["ids"] = [p["id"] for p in products if p.get("id")]
        _catalog_cache["expires"] = now + CATALOG_TTL_SECONDS
    except Exception as e:
        log.warning("catalog unavailable for groundedness check: %s", e)
    return _catalog_cache["names"], _catalog_cache["ids"]


async def score(answer: str):
    """Return (score, matched_count), or (None, 0) when the answer is not about products."""
    if not answer or not PRODUCT_WORDS.search(answer):
        return None, 0
    names, ids = await _catalog()
    if not names:
        return None, 0
    lowered = answer.lower()
    matched = sum(1 for n in names if n in lowered) + sum(1 for i in ids if i in answer)
    return (1.0 if matched > 0 else 0.0), matched
