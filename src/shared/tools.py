#!/usr/bin/python

# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0

import json
import logging
import os

import httpx

BASE_URL = os.getenv("APPLICATION_ENDPOINT", "localhost:8080")
TIMEOUT = httpx.Timeout(10.0)

log = logging.getLogger("tools")


def unavailable(subject: str, exc: Exception, guidance: str) -> str:
    """What the model is told when the shop cannot answer.

    Under two constraints. It has to start with "Error", because agents._failed_tool keys on
    that prefix to decide the request failed. And it is read by a model that will paraphrase it
    to a shopper, so it says what to do next rather than what broke: passing the raw httpx text
    through put "Server error '500 Internal Server Error' for url 'http://frontend:8080/...'"
    into answers customers read, internal hostname and all.

    The technical detail is not lost, it is logged - where it is correlated to the trace and can
    be read in Dynatrace, rather than paraphrased by a language model.
    """
    log.warning("%s unavailable: %s", subject, exc)
    return f"Error: {subject} is unavailable right now. {guidance}"


NO_SERVER_TALK = (
    "Do not mention servers, errors, status codes or outages to the customer."
)


async def get_ads(category: str):
    """Fetch promotional ads for Astronomy Shop homepage.
    Eg : category: `telescopes` or `travel`"""
    url = f"http://{BASE_URL}/api/data"
    params = {"contextKeys": category}
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT) as client:
            res = await client.get(url, params=params)
            res.raise_for_status()
            return res.json()
    except Exception as e:
        return unavailable("promotions", e, "Answer without mentioning promotions. " + NO_SERVER_TALK)


async def add_to_cart(user_id: str, product_id: str, quantity: int = 1):
    """Add a product (product_id) to the shopping cart for a user (user_id)."""
    url = f"http://{BASE_URL}/api/cart"
    data = {
        "item": {
            "productId": product_id,
            "quantity": quantity,
        },
        "userId": user_id,
    }
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT) as client:
            res = await client.post(url, json=data)
            res.raise_for_status()
            return res.json()
    except Exception as e:
        return f"Error while adding product to cart: {e}"


async def get_cart(user_id: str):
    """Retrieve the current contents of a user's cart."""
    url = f"http://{BASE_URL}/api/cart"
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT) as client:
            # The frontend's cart API keys the cart by sessionId, not user_id.
            res = await client.get(url, params={"sessionId": user_id})
            res.raise_for_status()
            return res.json()
    except Exception as e:
        return unavailable(
            "the shopping cart",
            e,
            "Tell the customer you cannot read their cart at the moment. " + NO_SERVER_TALK,
        )


async def empty_cart(user_id: str):
    """Empty the shopping cart for a user."""
    url = f"http://{BASE_URL}/api/cart"
    payload = {"userId": user_id}
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT) as client:
            res = await client.request("DELETE", url, json=payload)
            res.raise_for_status()
            if res.status_code == 204 or not res.content:
                return {"status": "success", "message": f"Cart emptied for user {user_id}"}
            return res.json()
    except Exception as e:
        return f"Error while emptying cart: {e}"


async def list_products():
    """List all products available in the Astronomy Shop."""
    url = f"http://{BASE_URL}/api/products"
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT) as client:
            res = await client.get(url)
            res.raise_for_status()
            return res.json()
    except Exception as e:
        return unavailable(
            "the product catalogue",
            e,
            "Tell the customer you cannot browse the catalogue at the moment and ask them to "
            "try again shortly. Do not name products or prices from memory. " + NO_SERVER_TALK,
        )


async def get_product(product_id: str):
    """Get detailed information about a product using its ID. IDs come from list_products;
    a made-up ID is answered with the list of valid ones."""
    # The model regularly invents ids ("refractor_telescope_1"); the shop answers those with a
    # 500, which would count as a catalog outage. Check the id against the catalog first, so
    # only a shop that cannot answer at all is reported as an error.
    catalog = await list_products()
    if isinstance(catalog, str):
        return catalog
    known = {p.get("id") for p in catalog if isinstance(p, dict)}
    if product_id not in known:
        names = ", ".join(f"{p['id']} ({p.get('name')})" for p in catalog if isinstance(p, dict))[:1500]
        return f"No product has the id '{product_id}'. Valid ids: {names}"
    url = f"http://{BASE_URL}/api/products/{product_id}"
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT) as client:
            res = await client.get(url)
            res.raise_for_status()
            return res.json()
    except Exception as e:
        # The product is in the catalogue listing, so the model is holding its name and price
        # already and will happily recommend it from that. During the 2026-09-18 catalog
        # incident it recommended the Starsense Explorer, at the right price, while that exact
        # product was the one returning 500s. Say plainly that it is off the table.
        return unavailable(
            f"details for product {product_id}",
            e,
            "Do not recommend this product and do not quote its price, even if an earlier "
            "product list gave you one. Tell the customer this item is temporarily "
            "unavailable and offer a different product instead. " + NO_SERVER_TALK,
        )


async def checkout(checkout_person):
    """Checkout the user's cart and create an order.
    Takes request in the format {string user_id, string userCurrency, Address address, string email, CreditCardInfo creditCard}
    Where Address is {string streetAddress, string city, string state, string country, string zipCode} and
    CreditCardInfo is {string creditCardNumber, int32 creditCardCvv, int32 creditCardExpirationYear, int32 creditCardExpirationMonth}
    """
    url = f"http://{BASE_URL}/api/checkout"
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT) as client:
            res = await client.post(url, json=checkout_person)
            if not res.is_success:
                body = res.text.strip() or "<empty body>"
                user_id = checkout_person.get("userId", "<unknown>")
                return (
                    f"Checkout failed with HTTP {res.status_code} for user "
                    f"{user_id}: {body}. "
                    "Note: the user's cart must contain at least one item before "
                    "calling checkout; call add_to_cart first."
                )
            return res.json()
    except Exception as e:
        return f"Error while performing checkout: {e}"


async def get_supported_currencies():
    """List supported currencies in Astronomy Shop."""
    url = f"http://{BASE_URL}/api/currency"
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT) as client:
            res = await client.get(url)
            res.raise_for_status()
            return res.json()
    except Exception as e:
        return unavailable(
            "the currency list",
            e,
            "Answer in US dollars and do not offer a currency change. " + NO_SERVER_TALK,
        )


async def get_recommendations(product_id: str):
    """Get product recommendations for a user."""
    url = f"http://{BASE_URL}/api/recommendations"
    params = {"productIds": product_id}
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT) as client:
            res = await client.get(url, params=params)
            res.raise_for_status()
            return res.json()
    except Exception as e:
        return unavailable(
            "recommendations",
            e,
            "Answer from the product catalogue instead of recommending related items. " + NO_SERVER_TALK,
        )


async def get_shipping_quote(items, currency_code, address):
    """Get estimated shipping cost for a given address.
    `items`: list of {productId, quantity} (a single dict is also accepted).
    `currency_code`: ISO 4217 code, e.g. "USD".
    `address`: {streetAddress, city, state, country, zipCode}.
    """
    url = f"http://{BASE_URL}/api/shipping"

    if isinstance(items, dict):
        items = [items]

    normalised_items = []
    for it in items:
        if not isinstance(it, dict):
            return f"Error fetching shipping quote: invalid item {it!r}"
        product_id = it.get("productId") or it.get("product_id")
        quantity = it.get("quantity", 1)
        if not product_id:
            return "Error fetching shipping quote: each item must include productId"
        normalised_items.append({"productId": product_id, "quantity": quantity})

    params = {
        "itemList": json.dumps(normalised_items),
        "currencyCode": currency_code,
        "address": json.dumps(address),
    }
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT) as client:
            res = await client.get(url, params=params)
            if not res.is_success:
                body = res.text.strip() or "<empty body>"
                return f"Shipping quote failed with HTTP {res.status_code}: {body}. "
            return res.json()
    except Exception as e:
        return unavailable(
            "a shipping quote",
            e,
            "Tell the customer shipping cannot be quoted right now and that they can still "
            "continue. " + NO_SERVER_TALK,
        )
