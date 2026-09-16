// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register.data

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The three calls the pickup screen makes, behind an interface so the screen can be built and
 * driven before the order-pickup API exists (bluebox-demo#50).
 *
 * [LivePickupSource] is the real thing and the default. `StubPickupSource` is the development
 * stand-in, chosen only when the build sets `PICKUP_STUB`; see that file for how to delete it.
 */
interface PickupSource {

    /** Orders waiting at this store. The list route carries enough to render a row. */
    suspend fun readyOrders(storeId: String): ApiResult<List<PickupOrder>>

    /** One order in full, which is what the cashier checks against the bag. */
    suspend fun order(orderId: String): ApiResult<PickupOrder>

    /** Hands the order over. The register identity travels in the body, not a header. */
    suspend fun collect(orderId: String, registerId: String, cashierId: String): ApiResult<CollectResponse>
}

/**
 * Retrofit over the same OkHttp stack as the sale routes, so a pickup is auto-instrumented exactly
 * like a scan: a web-request event per call, with `url.full`, the status and the trace headers that
 * link the cashier's session to the order's backend trace.
 */
private interface PickupApi {

    @GET("api/orders/ready")
    suspend fun readyOrders(@Query("storeId") storeId: String): Response<ReadyOrdersResponse>

    @GET("api/orders/{orderId}")
    suspend fun order(@Path("orderId") orderId: String): Response<PickupOrder>

    @POST("api/orders/{orderId}/collect")
    suspend fun collect(
        @Path("orderId") orderId: String,
        @Body body: CollectRequest,
    ): Response<CollectResponse>
}

class LivePickupSource(retrofit: Retrofit) : PickupSource {

    private val api: PickupApi = retrofit.create(PickupApi::class.java)

    override suspend fun readyOrders(storeId: String): ApiResult<List<PickupOrder>> =
        apiCall { api.readyOrders(storeId) }.require { it.orders.orEmpty() }

    override suspend fun order(orderId: String): ApiResult<PickupOrder> =
        apiCall { api.order(orderId) }.require { it.takeIf { order -> !order.orderId.isNullOrBlank() } }

    override suspend fun collect(
        orderId: String,
        registerId: String,
        cashierId: String,
    ): ApiResult<CollectResponse> =
        apiCall { api.collect(orderId, CollectRequest(registerId, cashierId)) }.require { it }
}

/**
 * A 200 with no body, or with a payload the register cannot use, is as useless at the counter as a
 * 500, so it becomes a failure here rather than an empty screen.
 */
private fun <T : Any, R : Any> ApiResult<T?>.require(transform: (T) -> R?): ApiResult<R> = when (this) {
    is ApiResult.Failed -> this
    is ApiResult.Ok -> value?.let(transform)?.let { ApiResult.Ok(it) } ?: ApiResult.Failed(0)
}
