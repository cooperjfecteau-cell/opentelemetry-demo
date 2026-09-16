// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register.data

/**
 * Only the parts of Astro Shop's frontend API the register actually reads. Fields are nullable
 * because a catalog under fault answers with whatever it has, and a missing field must not crash
 * the counter.
 */
data class Money(
    val currencyCode: String? = null,
    /** proto3 JSON renders int64 as a string; Gson coerces either shape into a Long. */
    val units: Long? = null,
    val nanos: Int? = null,
)

data class Product(
    val id: String? = null,
    val name: String? = null,
    val priceUsd: Money? = null,
)

data class CartItem(val productId: String, val quantity: Int)

data class AddToCartRequest(val userId: String, val item: CartItem)

data class EmptyCartRequest(val userId: String)

data class Address(
    val streetAddress: String,
    val city: String,
    val state: String,
    val country: String,
    val zipCode: String,
)

data class CreditCardInfo(
    val creditCardNumber: String,
    val creditCardCvv: Int,
    val creditCardExpirationMonth: Int,
    val creditCardExpirationYear: Int,
)

data class CheckoutRequest(
    val userId: String,
    val userCurrency: String,
    val address: Address,
    val email: String,
    val creditCard: CreditCardInfo,
)

data class CheckoutResponse(val orderId: String? = null)

/** The price a product line is rung up at. */
fun Product.priceUsdAmount(): Double =
    (priceUsd?.units ?: 0L).toDouble() + (priceUsd?.nanos ?: 0) / 1_000_000_000.0

/**
 * Buy-online-pick-up-in-store, as the order-pickup API defines it (bluebox-demo#50):
 *
 * ```
 * GET  /api/orders/ready?storeId=0142
 * GET  /api/orders/{orderId}
 * POST /api/orders/{orderId}/collect   { registerId, cashierId }
 * ```
 *
 * Nullable throughout for the same reason as [Product]: the register renders whatever arrived and
 * never crashes the counter over a field the shop left out. `customerName` is the only field here
 * that looks like personal data, and it is not - the orders are synthetic and the names are
 * obviously invented (see StubPickupSource), so nothing on the pickup screen needs `dtMask`.
 */
data class PickupItem(
    val productId: String? = null,
    val name: String? = null,
    val quantity: Int? = null,
    /** Unit price, in the order's currency. The order's own `total` is the authoritative sum. */
    val price: Double? = null,
)

data class PickupOrder(
    val orderId: String? = null,
    val placedAt: String? = null,
    val storeId: String? = null,
    /** Only the single-order route sets this: `ready`, `collected` or `unknown`. */
    val status: String? = null,
    val customerName: String? = null,
    val itemCount: Int? = null,
    val total: Double? = null,
    val items: List<PickupItem>? = null,
)

data class ReadyOrdersResponse(val orders: List<PickupOrder>? = null)

data class CollectRequest(val registerId: String, val cashierId: String)

data class CollectResponse(
    val orderId: String? = null,
    val status: String? = null,
    val collectedAt: String? = null,
)

/** The shop's own item count when it sent one, and the lines' quantities when it did not. */
fun PickupOrder.countedItems(): Int = itemCount ?: items?.sumOf { it.quantity ?: 0 } ?: 0
