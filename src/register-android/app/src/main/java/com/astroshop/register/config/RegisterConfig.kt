// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register.config

import android.content.Context
import android.content.Intent
import com.astroshop.register.BuildConfig

/**
 * Everything a register is provisioned with, in one place, so the fleet driver can change it
 * without touching the screens.
 *
 * Precedence, lowest first:
 *  1. The build-time defaults in BuildConfig.
 *  2. Whatever a previous launch persisted.
 *  3. Intent extras on this launch. Maestro's `launchApp` passes them, so one APK serves all twelve
 *     register identities and a shift can rotate them without reinstalling.
 *
 * Extras seen on a launch are persisted, so a relaunch with no extras — a crash restart, or the
 * launcher icon — keeps the identity the fleet last assigned.
 *
 * The checkout identity (store address, store email, house card) goes to Astro Shop's checkout and
 * nowhere else. It must never reach a Dynatrace event or a log.
 */
data class RegisterConfig(
    val storeId: String,
    val storeName: String,
    val storeRegion: String,
    val registerId: String,
    /** Pre-filled on the sign-in screen, so a driver or presenter only has to enter a PIN. */
    val defaultCashierNumber: String,
) {
    companion object {
        const val EXTRA_STORE_ID = "store"
        const val EXTRA_STORE_NAME = "storeName"
        const val EXTRA_STORE_REGION = "region"
        const val EXTRA_REGISTER_ID = "register"
        const val EXTRA_CASHIER_NUMBER = "cashier"

        private const val PREFS = "astroshop.register.provisioning"

        @Volatile
        private var current: RegisterConfig? = null

        /** The config resolved at launch. Read freely after [resolve]; the screens all do. */
        fun get(): RegisterConfig = current ?: error("RegisterConfig.resolve() has not run yet")

        fun resolve(context: Context, intent: Intent?): RegisterConfig {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            fun pick(extra: String, default: String): String =
                intent?.getStringExtra(extra)?.takeIf { it.isNotBlank() }
                    ?: prefs.getString(extra, null)?.takeIf { it.isNotBlank() }
                    ?: default

            val storeId = normalizeStoreId(pick(EXTRA_STORE_ID, BuildConfig.DEFAULT_STORE_ID))
            // A register moved to another store gets a register id in that store, not the build's.
            val registerId = intent?.getStringExtra(EXTRA_REGISTER_ID)?.takeIf { it.isNotBlank() }
                ?: prefs.getString(EXTRA_REGISTER_ID, null)?.takeIf { it.isNotBlank() }
                ?: if (intent?.hasExtra(EXTRA_STORE_ID) == true) "$storeId-03" else BuildConfig.DEFAULT_REGISTER_ID

            val resolved = RegisterConfig(
                storeId = storeId,
                storeName = pick(EXTRA_STORE_NAME, BuildConfig.DEFAULT_STORE_NAME),
                storeRegion = pick(EXTRA_STORE_REGION, BuildConfig.DEFAULT_STORE_REGION),
                registerId = registerId,
                defaultCashierNumber = digits(pick(EXTRA_CASHIER_NUMBER, BuildConfig.DEFAULT_CASHIER_NUMBER)),
            )

            prefs.edit()
                .putString(EXTRA_STORE_ID, resolved.storeId)
                .putString(EXTRA_STORE_NAME, resolved.storeName)
                .putString(EXTRA_STORE_REGION, resolved.storeRegion)
                .putString(EXTRA_REGISTER_ID, resolved.registerId)
                .putString(EXTRA_CASHIER_NUMBER, resolved.defaultCashierNumber)
                .apply()

            current = resolved
            return resolved
        }

        private fun digits(value: String) = value.filter { it.isDigit() }

        /** Zero-padded to 4 digits, so leading zeros survive into Grail as a string. */
        private fun normalizeStoreId(value: String) = digits(value).padStart(4, '0').takeLast(4)
    }
}
