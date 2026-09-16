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
