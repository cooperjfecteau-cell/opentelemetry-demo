// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register.rum

import com.astroshop.register.config.RegisterConfig
import com.dynatrace.agent.api.EventData
import com.dynatrace.agent.api.EventModifier
import com.dynatrace.agent.api.SessionPropertyEventData
import com.dynatrace.android.agent.Dynatrace
import com.dynatrace.android.agent.conf.DataCollectionLevel
import com.dynatrace.android.agent.conf.UserPrivacyOptions
import com.dynatrace.android.api.Configuration
import com.dynatrace.android.api.DynatraceSessionReplay
import com.dynatrace.android.api.privacy.MaskingConfiguration
import org.json.JSONObject

/**
 * Every Dynatrace call the register makes, in one file, implementing
 * `docs/register-rum-contract.md` (branch `register/rum-contract`) against the Android agent.
 *
 * The principle from bluebox-demo#33: the agent captures behaviour, the register adds meaning.
 * Taps, OkHttp requests, crashes, ANRs and app starts arrive with no code here. What the agent
 * cannot know is which store and register this is, how a sale ended, and why a lookup failed.
 *
 * Two things here are not obvious, and between them they hid every custom property this app sent
 * (bluebox-demo#49):
 *  - **Property keys must carry their own namespace.** `addEventProperty` and `addSessionProperty`
 *    prefix nothing; the agent drops any key that does not already start with `event_properties.`
 *    or `session_properties.`, silently apart from one `dtxEventGeneration` line. Hence [EVENT] and
 *    [SESSION] below - never pass a bare key.
 *  - **An event modifier may only write the namespace the event it modifies already owns.**
 *    See [addStoreAndRegisterModifier].
 *
 * Business events for the register flow live in [RegisterFlow], because they take a different road
 * out of the agent and land in `bizevents`, not `user.events`.
 */
object RegisterRum {

    /** View names, fixed by the contract. */
    const val VIEW_SIGN_IN = "sign_in"
    const val VIEW_SALE = "sale"
    const val VIEW_TENDER = "tender"
    const val VIEW_RECEIPT = "receipt"

    /** The two counter tasks that are not a sale (bluebox-demo#51). */
    const val VIEW_PICKUP = "pickup"
    const val VIEW_ASSISTANT = "assistant"

    /**
     * The namespaces the agent demands on every custom property key.
     *
     * `EventData.addEventProperty("amount", 21.95)` does **not** become `event_properties.amount`:
     * the agent compares the key against the namespace and throws the property away when it does
     * not match, leaving an event carrying nothing but `dt.support.api.has_dropped_properties`.
     * That is why the register's three custom events reached the tenant with no `event_name` to
     * recognise them by.
     */
    private const val EVENT = "event_properties."
    private const val SESSION = "session_properties."

    private const val EVENT_TRANSACTION_COMPLETE = "transaction_complete"
    private const val EVENT_TRANSACTION_VOID = "transaction_void"
    private const val EVENT_LOOKUP_FAILED = "lookup_failed"

    internal const val CURRENCY = "USD"

    // The agent re-reads privacy options on every session it opens, so the register keeps the one
    // set of options it ever uses and re-applies it verbatim to force a session boundary.
    private val privacyOptions: UserPrivacyOptions
        get() = UserPrivacyOptions.builder()
            .withDataCollectionLevel(DataCollectionLevel.USER_BEHAVIOR)
            .withCrashReportingOptedIn(true)
            // Layer three of Session Replay, after the Gradle flag and the tenant setting. Without
            // it the agent records nothing, however the other two are set.
            .withScreenRecordOptedIn(true)
            .build()

    /**
     * Once, from Application.onCreate, before any screen exists.
     *
     * Session Replay masking is code-only and applied before capture, so a mistake here ships in
     * the APK and cannot be fixed retroactively (bluebox-demo#40). Safe is a deliberate choice over
     * the Safest default: Safest blacks out labels as well as editable text, which would hide the
     * basket, the totals and the failure banner - the three things this demo exists to show. Safe
     * leaves them readable and still masks editable fields, and the PIN keypad and cashier id carry
     * `dtMask` by hand on top of that (see ui/screens/SignInScreen.kt).
     */
    fun onAppStart() {
        // Required by userOptIn(true) in the Gradle config, and the only carrier for
        // withScreenRecordOptedIn.
        Dynatrace.applyUserPrivacyOptions(privacyOptions)

        DynatraceSessionReplay.setConfiguration(
            Configuration.builder()
                .withMaskingConfiguration(MaskingConfiguration.Safe())
                .build()
        )

        addStoreAndRegisterModifier()
    }

    /**
     * Contract: an event modifier stamps `store_id` and `register_id` onto every event, including
     * the automatic ones, so a failed request can be attributed to a store without joining back to
     * the session.
     *
     * It writes **event properties only**. An earlier version also wrote `session_properties.*`
     * here, as a hedge against the agent's idle timeout splitting a shift into a session that never
     * saw sign-in. That hedge never worked: the modifier merge accepts only the namespace the event
     * being modified already owns, so on every ordinary event the agent logged
     * `sanitation: key 'session_properties.store_id' is outside of allowed namespace and thus
     * dropped` and set `dt.support.api.has_dropped_custom_properties` on the event - on *all* 611
     * of 612 events in a day, which is what made bluebox-demo#49 look like a tenant fault.
     *
     * The hedge that does work is re-sending the identity when a sale opens: [applySessionIdentity].
     *
     * The same rule cuts the other way, which is why the session-property events are skipped: they
     * own `session_properties.`, so writing event properties onto them would be dropped in turn and
     * would put the same support flag back on three events a shift. They already carry store and
     * register by hand.
     */
    private fun addStoreAndRegisterModifier() {
        Dynatrace.addEventModifier(EventModifier { event: JSONObject ->
            if (!carriesSessionProperties(event)) {
                val config = RegisterConfig.get()
                event.put(EVENT + "store_id", config.storeId)
                event.put(EVENT + "register_id", config.registerId)
            }
            event
        })
    }

    /** True for the events [applySessionIdentity] sends, which own the `session_properties.` side. */
    private fun carriesSessionProperties(event: JSONObject): Boolean =
        event.keys().asSequence().any { it.startsWith(SESSION) }

    /**
     * Contract: a Dynatrace session is one cashier shift on one register.
     *
     * `user.identifier` is hidden without the sensitive-data permission, so the cashier also rides
     * as a session property.
     */
    fun signIn(cashierId: String) {
        Dynatrace.identifyUser(cashierId)
        applySessionIdentity(cashierId)
    }

    /**
     * The five session properties, which the register may send more than once a shift.
     *
     * The agent's own idle timeout is shorter than the register's 15-minute lock, so a quiet shift
     * can split into a second session that never saw sign-in. Re-sending when a sale opens costs
     * one event and keeps store, register and cashier on the session either way.
     */
    fun applySessionIdentity(cashierId: String) {
        val config = RegisterConfig.get()
        Dynatrace.sendSessionPropertyEvent(
            SessionPropertyEventData()
                .addSessionProperty(SESSION + "store_id", config.storeId)
                .addSessionProperty(SESSION + "store_name", config.storeName)
                .addSessionProperty(SESSION + "store_region", config.storeRegion)
                .addSessionProperty(SESSION + "register_id", config.registerId)
                .addSessionProperty(SESSION + "cashier_id", cashierId)
        )
    }

    /**
     * The contract's `endSession()`, which does not exist on the Android agent: `endVisit()` is
     * RUM Classic only (bluebox-demo#40).
     *
     * What this does instead: re-apply the same privacy options. Its javadoc is explicit that it
     * "creates a new session with the specified privacy settings", which closes the shift's session
     * and opens a fresh, unidentified one. Clearing the user tag afterwards keeps the next
     * cashier's shift from inheriting this one's identity.
     *
     * Called at sign-out and at the 15-minute idle lock, the two boundaries the contract names.
     */
    fun endShiftSession() {
        // The session's last events would otherwise be dropped when the boundary cuts them off.
        Dynatrace.flushEvents()
        Dynatrace.applyUserPrivacyOptions(privacyOptions)
        Dynatrace.identifyUser(null)
    }

    /**
     * Contract: automatic view detection covers Activities only, so Compose screens are named by
     * hand on every navigation. `startView` returns void and stops the previous view itself, so
     * there is nothing to close.
     */
    fun startView(name: String) {
        Dynatrace.startView(name)
    }

    /**
     * Contract: `transaction_complete`, an outcome event. `EventData` has no name field, so the
     * name travels as `event_name`.
     *
     * `transaction_id` is new: it is the join between a cashier's RUM session and the sale's
     * business events, so a step in the Business Flow can be opened as a session replay.
     */
    fun transactionComplete(transactionId: String, amount: Double, itemCount: Int, orderId: String) {
        Dynatrace.sendEvent(
            EventData()
                .addEventProperty(EVENT + "event_name", EVENT_TRANSACTION_COMPLETE)
                .addEventProperty(EVENT + "transaction_id", transactionId)
                .addEventProperty(EVENT + "amount", amount)
                .addEventProperty(EVENT + "currency", CURRENCY)
                .addEventProperty(EVENT + "item_count", itemCount)
                .addEventProperty(EVENT + "order_id", orderId)
        )
    }

    /** Contract: `transaction_void`. `reason` is `cashier` or `error`. */
    fun transactionVoid(
        transactionId: String,
        reason: String,
        itemCount: Int,
        failedLookupCount: Int,
    ) {
        Dynatrace.sendEvent(
            EventData()
                .addEventProperty(EVENT + "event_name", EVENT_TRANSACTION_VOID)
                .addEventProperty(EVENT + "transaction_id", transactionId)
                .addEventProperty(EVENT + "void_reason", reason)
                .addEventProperty(EVENT + "item_count", itemCount)
                .addEventProperty(EVENT + "failed_lookup_count", failedLookupCount)
        )
    }

    /**
     * Contract: `lookup_failed`, only on failure, so it stays quiet in normal traffic.
     *
     * `result` is the whole point of this event. Astro Shop returns 500 for an unknown product id
     * as well as for a real catalog outage, so the automatic request events cannot tell a mistyped
     * id from the fault.
     */
    fun lookupFailed(productId: String, result: String, statusCode: Int) {
        Dynatrace.sendEvent(
            EventData()
                .addEventProperty(EVENT + "event_name", EVENT_LOOKUP_FAILED)
                .addEventProperty(EVENT + "product_id", productId)
                .addEventProperty(EVENT + "result", result)
                .addEventProperty(EVENT + "status_code", statusCode)
        )
    }
}
