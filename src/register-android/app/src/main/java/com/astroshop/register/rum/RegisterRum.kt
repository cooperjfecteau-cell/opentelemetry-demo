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
 * The Android agent differs from the React Native plugin the contract was first written against:
 * there is no `endSession()`, and views are never auto-detected for Compose. See [endShiftSession]
 * and [startView].
 */
object RegisterRum {

    /** View names, fixed by the contract. */
    const val VIEW_SIGN_IN = "sign_in"
    const val VIEW_SALE = "sale"
    const val VIEW_TENDER = "tender"
    const val VIEW_RECEIPT = "receipt"

    private const val EVENT_TRANSACTION_COMPLETE = "transaction_complete"
    private const val EVENT_TRANSACTION_VOID = "transaction_void"
    private const val EVENT_LOOKUP_FAILED = "lookup_failed"

    private const val CURRENCY = "USD"

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
     * It also re-writes the session properties, which the Android modifier may do and the React
     * Native one may not. The agent's own idle timeout is shorter than the register's 15-minute
     * lock, so a quiet shift can split into a second session that never saw sign-in; the modifier
     * is what keeps store and register on its events either way.
     */
    private fun addStoreAndRegisterModifier() {
        Dynatrace.addEventModifier(EventModifier { event: JSONObject ->
            val config = RegisterConfig.get()
            event.put("event_properties.store_id", config.storeId)
            event.put("event_properties.register_id", config.registerId)
            event.put("session_properties.store_id", config.storeId)
            event.put("session_properties.register_id", config.registerId)
            event
        })
    }

    /**
     * Contract: a Dynatrace session is one cashier shift on one register.
     *
     * `user.identifier` is hidden without the sensitive-data permission, so the cashier also rides
     * as a session property.
     */
    fun signIn(cashierId: String) {
        Dynatrace.identifyUser(cashierId)

        val config = RegisterConfig.get()
        Dynatrace.sendSessionPropertyEvent(
            SessionPropertyEventData()
                .addSessionProperty("store_id", config.storeId)
                .addSessionProperty("store_name", config.storeName)
                .addSessionProperty("store_region", config.storeRegion)
                .addSessionProperty("register_id", config.registerId)
                .addSessionProperty("cashier_id", cashierId)
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
     *
     * Unverified: no Dynatrace page recommends this for ending a session, and no tenant was
     * available. The first live run must confirm that a sign-out produces two distinct
     * `dt.rum.session.id` values.
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
     */
    fun transactionComplete(amount: Double, itemCount: Int, orderId: String) {
        Dynatrace.sendEvent(
            EventData()
                .addEventProperty("event_name", EVENT_TRANSACTION_COMPLETE)
                .addEventProperty("amount", amount)
                .addEventProperty("currency", CURRENCY)
                .addEventProperty("item_count", itemCount)
                .addEventProperty("order_id", orderId)
        )
    }

    /** Contract: `transaction_void`. `reason` is `cashier` or `error`. */
    fun transactionVoid(reason: String, itemCount: Int, failedLookupCount: Int) {
        Dynatrace.sendEvent(
            EventData()
                .addEventProperty("event_name", EVENT_TRANSACTION_VOID)
                .addEventProperty("void_reason", reason)
                .addEventProperty("item_count", itemCount)
                .addEventProperty("failed_lookup_count", failedLookupCount)
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
                .addEventProperty("event_name", EVENT_LOOKUP_FAILED)
                .addEventProperty("product_id", productId)
                .addEventProperty("result", result)
                .addEventProperty("status_code", statusCode)
        )
    }
}
