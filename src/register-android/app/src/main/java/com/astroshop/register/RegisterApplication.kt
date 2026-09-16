// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register

import android.app.Application
import com.astroshop.register.config.RegisterConfig
import com.astroshop.register.rum.RegisterRum

/**
 * The agent itself is started by the Gradle plugin's auto-start configuration, so there is no
 * startup call here. What is left is the privacy opt-in (which carries the Session Replay runtime
 * flag), the replay masking level, and the event modifier - all of which must be in place before
 * the first screen renders, because masking is applied before capture.
 */
class RegisterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The modifier reads the config on every event, so it has to exist first. Launch extras
        // refine it a moment later in MainActivity.
        RegisterConfig.resolve(this, null)
        RegisterRum.onAppStart()
    }
}
