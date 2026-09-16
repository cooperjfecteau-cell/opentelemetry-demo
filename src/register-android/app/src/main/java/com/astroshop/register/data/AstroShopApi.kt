// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The register calls the frontend's own Next.js routes through frontend-proxy — the same ones web
 * shoppers use, so the catalog fault reaches both (bluebox-demo#18). There is no register service.
 *
 * Retrofit over OkHttp on purpose: the Dynatrace agent auto-instruments OkHttp 3/4/5, so every call
 * here becomes a web-request event carrying `url.full` and the W3C trace headers that link the
 * session to the backend trace. Nothing in this file touches Dynatrace directly.
 */
interface AstroShopApi {

    @GET("api/products/{productId}")
    suspend fun getProduct(
        @Path("productId") productId: String,
        @Query("currencyCode") currencyCode: String,
    ): Response<Product>

    @POST("api/cart")
    suspend fun addToCart(
        @Query("currencyCode") currencyCode: String,
        @Body body: AddToCartRequest,
    ): Response<Unit>

    // The cart route takes the session key in a body on DELETE, which @DELETE cannot carry.
    @HTTP(method = "DELETE", path = "api/cart", hasBody = true)
    suspend fun emptyCart(@Body body: EmptyCartRequest): Response<Unit>

    @POST("api/checkout")
    suspend fun checkout(
        @Query("currencyCode") currencyCode: String,
        @Body body: CheckoutRequest,
    ): Response<CheckoutResponse>
}
