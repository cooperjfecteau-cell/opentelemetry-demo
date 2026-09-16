// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register.rum

import com.astroshop.register.config.RegisterConfig
import com.dynatrace.android.agent.Dynatrace
import org.json.JSONObject

/**
 * The register's **business events**, which are what a Dynatrace Business Flow is built from.
 *
 * Why these exist next to the RUM custom events in [RegisterRum]: Business Flows read `bizevents`,
 * not `user.events`, and the two are different tables reached by different agent APIs. The RUM
 * events stay because they are the session story - what a cashier did, and what replay shows. These
 * are the money story - what the sale did, step by step, with revenue on the last step.
 *
 * `Dynatrace.sendBizEvent(type, attributes)` is filed under RUM Classic in the plugin README, so
 * whether it emits at all for a New-RUM-only frontend was tested before this was designed around
 * (bluebox-demo#49): it does, and the events land in `bizevents` with `event.provider` set to the
 * frontend's application id.
 *
 * ### The flow
 *
 * One **case per sale**, correlated on `transaction.id`:
 *
 *  1. [saleOpened] - the register opens a sale. `sale.opened_by` distinguishes the first sale of a
 *     shift, which a cashier sign-in opens, from every later one opened by "New sale".
 *  2. [itemAdded] - repeatable, once per item that reached the basket.
 *  3. [tenderStarted] - the cashier moved to Tender and chose card or cash.
 *  4. [saleCompleted] - Astro Shop returned an order id. Carries `sale.amount`, so this is the step
 *     the flow shows money on.
 *
 * [saleVoided] is not a step; it is the drop-off reason, so a sale that leaves the flow between
 * steps 2 and 4 can be explained rather than merely counted.
 *
 * Two further events take the same road out of the agent without being steps of this flow: the
 * pickup pair ([pickupStarted], [pickupCollected]) for an online order collected at the counter,
 * and [assistantAsked] for a question put to the shop assistant (bluebox-demo#51). Both carry the
 * register identity and neither carries `transaction.id`; each says why in its own note.
 *
 * ### Attribute names
 *
 * Dotted and flat. `event.*` is reserved by the platform (`event.type`, `event.provider`,
 * `event.id`), so nothing here uses that prefix. Every event carries the full register identity, so
 * a flow can be filtered or split by store without joining anything.
 */
object RegisterFlow {

    private const val TYPE_SALE_OPENED = "com.astroshop.register.sale.opened"
    private const val TYPE_ITEM_ADDED = "com.astroshop.register.sale.item_added"
    private const val TYPE_TENDER_STARTED = "com.astroshop.register.sale.tender_started"
    private const val TYPE_SALE_COMPLETED = "com.astroshop.register.sale.completed"
    private const val TYPE_SALE_VOIDED = "com.astroshop.register.sale.voided"

    // Off the sale flow, and deliberately so: see [pickupStarted] and [assistantAsked].
    private const val TYPE_PICKUP_STARTED = "com.astroshop.register.pickup.started"
    private const val TYPE_PICKUP_COLLECTED = "com.astroshop.register.pickup.collected"
    private const val TYPE_ASSISTANT_ASKED = "com.astroshop.register.assistant.asked"

    /** `sale.opened_by` values. The first sale of a shift is the one the sign-in opened. */
    const val OPENED_BY_SIGN_IN = "sign_in"
    const val OPENED_BY_NEW_SALE = "new_sale"

    /** [assistantAsked]'s `assistant.result` values. */
    const val ASSISTANT_OK = "ok"
    const val ASSISTANT_ERROR = "error"

    /**
     * Step 1. Emitted at sign-in for a shift's first sale and on "New sale" for every later one, so
     * that every case in the flow has a first step and conversion means "sales that took money".
     */
    fun saleOpened(transactionId: String, cashierId: String, openedBy: String) {
        Dynatrace.sendBizEvent(
            TYPE_SALE_OPENED,
            identity(transactionId, cashierId).put("sale.opened_by", openedBy),
        )
    }

    /** Step 2, repeatable. `item.count` is the basket size after this item, not the quantity added. */
    fun itemAdded(
        transactionId: String,
        cashierId: String,
        productId: String,
        productName: String,
        unitPrice: Double,
        itemCount: Int,
    ) {
        Dynatrace.sendBizEvent(
            TYPE_ITEM_ADDED,
            identity(transactionId, cashierId)
                .put("item.product_id", productId)
                .put("item.name", productName)
                .put("item.unit_price", unitPrice)
                .put("item.count", itemCount)
                .put("currency", RegisterRum.CURRENCY),
        )
    }

    /**
     * Step 3. `tender.type` is `card` or `cash`.
     *
     * The amount is `sale.amount_due`, not `sale.amount`: only the completing step may carry
     * `sale.amount`, or a query that sums it across the flow reports every sale twice.
     */
    fun tenderStarted(
        transactionId: String,
        cashierId: String,
        tenderType: String,
        amountDue: Double,
        itemCount: Int,
    ) {
        Dynatrace.sendBizEvent(
            TYPE_TENDER_STARTED,
            identity(transactionId, cashierId)
                .put("tender.type", tenderType)
                .put("sale.amount_due", amountDue)
                .put("item.count", itemCount)
                .put("currency", RegisterRum.CURRENCY),
        )
    }

    /**
     * Step 4, the converting step. `sale.amount` is the revenue the flow reports, and `order.id`
     * is Astro Shop's own order, which is what ties this sale to the backend trace that made it.
     */
    fun saleCompleted(
        transactionId: String,
        cashierId: String,
        amount: Double,
        itemCount: Int,
        tenderType: String,
        orderId: String,
    ) {
        Dynatrace.sendBizEvent(
            TYPE_SALE_COMPLETED,
            identity(transactionId, cashierId)
                .put("sale.amount", amount)
                .put("item.count", itemCount)
                .put("tender.type", tenderType)
                .put("order.id", orderId)
                .put("currency", RegisterRum.CURRENCY),
        )
    }

    /**
     * Not a step: why a case left the flow. `sale.void_reason` is `cashier` or `error`, and
     * `sale.failed_lookup_count` is what separates a cashier changing their mind from the catalog
     * fault driving them to give up.
     */
    fun saleVoided(
        transactionId: String,
        cashierId: String,
        reason: String,
        itemCount: Int,
        failedLookupCount: Int,
    ) {
        Dynatrace.sendBizEvent(
            TYPE_SALE_VOIDED,
            identity(transactionId, cashierId)
                .put("sale.void_reason", reason)
                .put("item.count", itemCount)
                .put("sale.failed_lookup_count", failedLookupCount),
        )
    }

    /**
     * A cashier opened an online order at the counter: the pickup half of buy-online-pick-up-in-store
     * (bluebox-demo#51). Emitted when the order's contents are on the screen, not when the list is,
     * because until then there is no order to name.
     *
     * **Not a step in the sale flow, and it carries no `transaction.id`.** A pickup is a different
     * case with a different correlation key - the order - and putting it on the open sale would add
     * events to a case the flow's steps do not describe, which is how a conversion number stops
     * meaning anything. The two are joined by the session they happen in, as they are at the
     * counter.
     */
    fun pickupStarted(cashierId: String, orderId: String, itemCount: Int, total: Double) {
        Dynatrace.sendBizEvent(
            TYPE_PICKUP_STARTED,
            pickupPayload(cashierId, orderId, itemCount, total),
        )
    }

    /** The order was handed over and the shop was told so. The pair with [pickupStarted]. */
    fun pickupCollected(cashierId: String, orderId: String, itemCount: Int, total: Double) {
        Dynatrace.sendBizEvent(
            TYPE_PICKUP_COLLECTED,
            pickupPayload(cashierId, orderId, itemCount, total),
        )
    }

    /**
     * `order.total` rather than `sale.amount`: the money was taken online, so counting it here
     * would report the shop's revenue twice.
     */
    private fun pickupPayload(cashierId: String, orderId: String, itemCount: Int, total: Double) =
        registerIdentity(cashierId)
            .put("order.id", orderId)
            .put("item.count", itemCount)
            .put("order.total", total)
            .put("currency", RegisterRum.CURRENCY)

    /**
     * The cashier asked the shop assistant about an item. One event per question, whatever came
     * back.
     *
     * `assistant.response_time_ms` is measured at the counter, so it is what the cashier waited:
     * the round trip through frontend-proxy and the chatbot to the assistant and its model, not the
     * assistant's own span duration. `assistant.result` is `ok` or `error`, because a question that
     * failed after twenty seconds and one answered in twenty look identical without it.
     *
     * Like the pickup events, this is not a step in the sale flow: a cashier asks about an item
     * whether or not it ends up in a basket.
     */
    fun assistantAsked(cashierId: String, productId: String, responseTimeMs: Long, result: String) {
        Dynatrace.sendBizEvent(
            TYPE_ASSISTANT_ASKED,
            registerIdentity(cashierId)
                .put("product.id", productId)
                .put("assistant.response_time_ms", responseTimeMs)
                .put("assistant.result", result),
        )
    }

    /**
     * Who and where, on every event this object sends: the register identity the fleet provisions
     * at launch, plus the cashier on shift. Every event carries it, so a query can be filtered or
     * split by store without joining anything.
     */
    private fun registerIdentity(cashierId: String): JSONObject {
        val config = RegisterConfig.get()
        return JSONObject()
            .put("store.id", config.storeId)
            .put("store.name", config.storeName)
            .put("store.region", config.storeRegion)
            .put("register.id", config.registerId)
            .put("cashier.id", cashierId)
    }

    /** The same, plus the flow's correlation key, which a step is never without. */
    private fun identity(transactionId: String, cashierId: String): JSONObject =
        registerIdentity(cashierId).put("transaction.id", transactionId)
}
