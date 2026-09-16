// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register.data

import com.astroshop.register.BuildConfig
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What a call to Astro Shop came back with, flattened so the screens never see an exception. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    /** `status` is 0 when the call never reached a server (DNS, timeout, refused). */
    data class Failed(val status: Int) : ApiResult<Nothing>
}

/**
 * Runs one Astro Shop call and flattens it into an [ApiResult]. Top level so that every caller in
 * this package - the sale's routes and the pickup source alike - fails the same way.
 */
internal suspend fun <T> apiCall(block: suspend () -> Response<T>): ApiResult<T?> =
    try {
        val response = block()
        if (response.isSuccessful) ApiResult.Ok(response.body()) else ApiResult.Failed(response.code())
    } catch (e: IOException) {
        // A transport failure is indistinguishable from a 5xx at the counter, and the agent has
        // already recorded the failed request either way.
        ApiResult.Failed(0)
    }

/**
 * The register's Astro Shop calls, and the cart key that keeps register carts apart from shopper
 * carts (bluebox-demo#18).
 *
 * No interceptor logs anything. The checkout payload carries the store address and the house card,
 * and neither may ever reach a log line or a Dynatrace event.
 */
class RegisterRepository(baseUrl: String = BuildConfig.ASTROSHOP_BASE_URL) {

    // Retrofit needs the trailing slash to resolve the relative paths in AstroShopApi.
    private val root = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(root)
        .client(
            OkHttpClient.Builder()
                // A counter cannot wait on a hung catalog; the fault has to surface as a banner.
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api: AstroShopApi = retrofit.create(AstroShopApi::class.java)

    /**
     * Order pickup, against the API in bluebox-demo#50.
     *
     * The stub is a development stand-in for an API that did not exist yet, and it is the whole of
     * the choice: delete the branch, the `PICKUP_STUB` field in `app/build.gradle.kts` and
     * `StubPickupSource.kt`, and the register has only the real thing. A release build already does,
     * because the flag defaults to false.
     */
    val pickup: PickupSource =
        if (BuildConfig.PICKUP_STUB) StubPickupSource() else LivePickupSource(retrofit)

    /** The shop assistant. Its own client: the assistant is slow where the catalog must not be. */
    val assistant: AssistantClient = AssistantClient(root)

    /** The cart key for this cashier session, set at sign-in. */
    @Volatile
    var cartSessionId: String = ""

    suspend fun lookupProduct(productId: String): ApiResult<Product?> =
        apiCall { api.getProduct(productId, CURRENCY) }

    suspend fun addToCart(productId: String, quantity: Int): ApiResult<Unit?> =
        apiCall { api.addToCart(CURRENCY, AddToCartRequest(cartSessionId, CartItem(productId, quantity))) }

    suspend fun emptyCart(): ApiResult<Unit?> =
        apiCall { api.emptyCart(EmptyCartRequest(cartSessionId)) }

    /**
     * Card or cash, the order goes to checkout with the store address and email and the house test
     * card, so payment runs and every sale produces a full backend trace.
     */
    suspend fun checkout(): ApiResult<CheckoutResponse?> = apiCall {
        api.checkout(
            CURRENCY,
            CheckoutRequest(
                userId = cartSessionId,
                userCurrency = CURRENCY,
                address = Address(
                    streetAddress = BuildConfig.STORE_STREET_ADDRESS,
                    city = BuildConfig.STORE_CITY,
                    state = BuildConfig.STORE_STATE,
                    country = BuildConfig.STORE_COUNTRY,
                    zipCode = BuildConfig.STORE_ZIP_CODE,
                ),
                email = BuildConfig.STORE_EMAIL,
                creditCard = CreditCardInfo(
                    creditCardNumber = BuildConfig.HOUSE_CARD_NUMBER,
                    creditCardCvv = BuildConfig.HOUSE_CARD_CVV,
                    creditCardExpirationMonth = BuildConfig.HOUSE_CARD_EXPIRATION_MONTH,
                    creditCardExpirationYear = BuildConfig.HOUSE_CARD_EXPIRATION_YEAR,
                ),
            ),
        )
    }

    private companion object {
        const val CURRENCY = "USD"
    }
}
