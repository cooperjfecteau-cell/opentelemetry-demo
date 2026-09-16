// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register.data

import com.astroshop.register.config.BARCODE_SHEET
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.random.Random

/**
 * A stand-in for the order-pickup API (bluebox-demo#50) while it is being built, so the pickup
 * screen can be written and driven end to end before the shop can answer.
 *
 * It answers the same three shapes as [LivePickupSource] and nothing else, which is the point: the
 * screen and the view model cannot tell the two apart, so the switch is one build flag.
 *
 * **Deleting the stub** is three edits, all of them local:
 *  1. delete this file,
 *  2. delete the `PICKUP_STUB` `buildConfigField` in `app/build.gradle.kts`,
 *  3. delete the one branch that reads it in [RegisterRepository].
 *
 * The customer names are invented on purpose and are not anybody's: the pickup screen shows the
 * name unmasked, so it must never be real personal data (bluebox-demo#51).
 */
class StubPickupSource : PickupSource {

    private val orders = ConcurrentHashMap<String, PickupOrder>()
    private val random = Random(RANDOM_SEED)

    override suspend fun readyOrders(storeId: String): ApiResult<List<PickupOrder>> {
        settle()
        seed(storeId)
        val ready = orders.values
            .filter { it.storeId == storeId && it.status == STATUS_READY }
            .sortedBy { it.placedAt }
            // The list route carries no `status`, exactly like the real one.
            .map { it.copy(status = null) }
        return ApiResult.Ok(ready)
    }

    override suspend fun order(orderId: String): ApiResult<PickupOrder> {
        settle()
        val order = orders[orderId] ?: return ApiResult.Ok(PickupOrder(orderId = orderId, status = STATUS_UNKNOWN))
        return ApiResult.Ok(order)
    }

    override suspend fun collect(
        orderId: String,
        registerId: String,
        cashierId: String,
    ): ApiResult<CollectResponse> {
        settle()
        val order = orders[orderId] ?: return ApiResult.Failed(404)
        val collectedAt = timestamp(System.currentTimeMillis())
        orders[orderId] = order.copy(status = STATUS_COLLECTED)
        return ApiResult.Ok(CollectResponse(orderId, STATUS_COLLECTED, collectedAt))
    }

    /** Orders exist only for stores a cashier has actually opened the screen at. */
    private fun seed(storeId: String) {
        if (orders.values.any { it.storeId == storeId }) return
        val now = System.currentTimeMillis()
        repeat(ORDERS_PER_STORE) { index ->
            val items = (1..(1 + random.nextInt(MAX_LINES))).map { line() }
            val order = PickupOrder(
                orderId = UUID.randomUUID().toString(),
                // Placed between twenty minutes and a few hours ago, newest last.
                placedAt = timestamp(now - (20L + index * 47L + random.nextInt(30)) * 60_000L),
                storeId = storeId,
                status = STATUS_READY,
                customerName = CUSTOMERS[(index + storeId.hashCode().let(::abs)) % CUSTOMERS.size],
                itemCount = items.sumOf { it.quantity ?: 0 },
                total = items.sumOf { (it.price ?: 0.0) * (it.quantity ?: 0) }.roundedToCents(),
                items = items,
            )
            orders[order.orderId!!] = order
        }
    }

    private fun line(): PickupItem {
        val product = BARCODE_SHEET[random.nextInt(BARCODE_SHEET.size)]
        return PickupItem(
            productId = product.id,
            name = product.name,
            quantity = 1 + random.nextInt(2),
            // Invented, and stable per product id: the stub never calls the catalog, because the
            // point of it is to work when the shop cannot answer at all.
            price = (19.95 + abs(product.id.hashCode()) % 30000 / 100.0).roundedToCents(),
        )
    }

    /** Enough delay that the spinner is real, and little enough that driving the app is not slow. */
    private suspend fun settle() = delay(250L + random.nextInt(350))

    private fun Double.roundedToCents(): Double = Math.round(this * 100) / 100.0

    private fun timestamp(millis: Long): String = ISO.format(Date(millis))

    private companion object {
        const val STATUS_READY = "ready"
        const val STATUS_COLLECTED = "collected"
        const val STATUS_UNKNOWN = "unknown"
        const val ORDERS_PER_STORE = 4
        const val MAX_LINES = 3
        const val RANDOM_SEED = 0x0142

        /** Invented shoppers. Not real people, and deliberately not plausible as real records. */
        val CUSTOMERS = listOf(
            "Nova Chandrasekhar",
            "Rigel Okonkwo",
            "Cassie Sparks",
            "Milo Perihelion",
            "Juno Vasquez-Bell",
            "Orin Lightyear",
        )

        val ISO: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
