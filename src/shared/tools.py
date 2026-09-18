#!/usr/bin/python

# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0

import json
import os

import httpx

BASE_URL = os.getenv("APPLICATION_ENDPOINT", "localhost:8080")
TIMEOUT = httpx.Timeout(10.0)


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
        return f"Error fetching ads: {e}"


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
        return f"Error while fetching cart: {e}"


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


def price_usd(product) -> float | None:
    """A product's price as a number.

    The catalogue gives money as {"units": 69, "nanos": 950000000}, which is exact and awkward.
    Asked to filter on it, the model reads the magnitude wrong: "products under 50 dollars"
    has been answered with a $69.95 solar filter and a $101.96 telescope.
    """
    price = product.get("priceUsd") if isinstance(product, dict) else None
    if not isinstance(price, dict):
        return None
    return round(price.get("units", 0) + price.get("nanos", 0) / 1_000_000_000, 2)


async def list_products(min_price: float | None = None, max_price: float | None = None):
    """List products available in the Astronomy Shop.

    Pass `max_price` and/or `min_price` in US dollars to filter by price - the comparison is
    done exactly, here, so use it instead of filtering the list yourself. For example, a
    customer asking for products under 50 dollars is `max_price=50`.

    Each product carries `price_usd`, its price as a plain number.
    """
    url = f"http://{BASE_URL}/api/products"
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT) as client:
            res = await client.get(url)
            res.raise_for_status()
            products = res.json()
    except Exception as e:
        return f"Error while fetching product list: {e}"

    if not isinstance(products, list):
        return products

    out = []
    for product in products:
        if not isinstance(product, dict):
            continue
        value = price_usd(product)
        product = {**product, "price_usd": value}
        # A product with no readable price cannot honestly be said to be under a limit, so it
        # is left out of a filtered answer rather than guessed at.
        if (min_price is not None or max_price is not None) and value is None:
            continue
        if min_price is not None and value < min_price:
            continue
        if max_price is not None and value > max_price:
            continue
        out.append(product)
    return out


async def get_product(product_id: str):
    """Get detailed information about a product using its ID. IDs come from list_products;
    a made-up ID is answered with the list of valid ones."""
    # The model regularly invents ids ("refractor_telescope_1"); the shop answers those with a
    # 500, which would count as a catalog outage. Check the id against the catalog first, so
    # only a shop that cannot answer at all is reported as an error.
    catalog = await list_products()
    if isinstance(catalog, str):
        return f"Error while fetching product {product_id}: {catalog}"
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
        return f"Error while fetching product {product_id}: {e}"


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
        return f"Error while fetching currency list: {e}"


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
        return f"Error fetching recommendations: {e}"


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
        return f"Error fetching shipping quote: {e}"
