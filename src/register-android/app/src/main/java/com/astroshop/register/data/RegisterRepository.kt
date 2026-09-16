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
 * The register's Astro Shop calls, and the cart key that keeps register carts apart from shopper
 * carts (bluebox-demo#18).
 *
 * No interceptor logs anything. The checkout payload carries the store address and the house card,
 * and neither may ever reach a log line or a Dynatrace event.
 */
class RegisterRepository(baseUrl: String = BuildConfig.ASTROSHOP_BASE_URL) {

    private val api: AstroShopApi = Retrofit.Builder()
        // Retrofit needs the trailing slash to resolve the relative paths in AstroShopApi.
        .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
        .client(
            OkHttpClient.Builder()
                // A counter cannot wait on a hung catalog; the fault has to surface as a banner.
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AstroShopApi::class.java)

    /** The cart key for this cashier session, set at sign-in. */
    @Volatile
    var cartSessionId: String = ""

    suspend fun lookupProduct(productId: String): ApiResult<Product?> =
        call { api.getProduct(productId, CURRENCY) }

    suspend fun addToCart(productId: String, quantity: Int): ApiResult<Unit?> =
        call { api.addToCart(CURRENCY, AddToCartRequest(cartSessionId, CartItem(productId, quantity))) }

    suspend fun emptyCart(): ApiResult<Unit?> =
        call { api.emptyCart(EmptyCartRequest(cartSessionId)) }

    /**
     * Card or cash, the order goes to checkout with the store address and email and the house test
     * card, so payment runs and every sale produces a full backend trace.
     */
    suspend fun checkout(): ApiResult<CheckoutResponse?> = call {
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

    private suspend fun <T> call(block: suspend () -> Response<T>): ApiResult<T?> =
        try {
            val response = block()
            if (response.isSuccessful) ApiResult.Ok(response.body()) else ApiResult.Failed(response.code())
        } catch (e: IOException) {
            // A transport failure is indistinguishable from a 5xx at the counter, and the agent has
            // already recorded the failed request either way.
            ApiResult.Failed(0)
        }

    private companion object {
        const val CURRENCY = "USD"
    }
}
