// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.astroshop.register.config.RegisterConfig
import com.astroshop.register.ui.RegisterApp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Provisioning arrives as launch extras, which only the Activity sees. Maestro's launchApp
        // passes them, so one APK serves all twelve register identities.
        RegisterConfig.resolve(this, intent)
        enableEdgeToEdge()
        setContent { RegisterApp() }
    }

    /**
     * singleTask, so a relaunch with new extras arrives here rather than in onCreate. The fleet
     * rotates identities between shifts this way, without reinstalling.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        RegisterConfig.resolve(this, intent)
    }
}
