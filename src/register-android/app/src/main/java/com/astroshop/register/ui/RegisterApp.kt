// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.viewmodel.compose.viewModel
import com.astroshop.register.ui.screens.ReceiptScreen
import com.astroshop.register.ui.screens.SaleScreen
import com.astroshop.register.ui.screens.SignInScreen
import com.astroshop.register.ui.screens.TenderScreen
import com.astroshop.register.ui.theme.AstroShopRegisterTheme
import com.astroshop.register.ui.theme.RegisterColors

/**
 * Four screens and no back stack: a register only ever moves forwards, and a sign-out returns it to
 * the start. Screen changes drive `Dynatrace.startView()` from the view model, because automatic
 * view detection covers Activities only and would report this whole app as one view.
 */
@Composable
fun RegisterApp(viewModel: RegisterViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    AstroShopRegisterTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(RegisterColors.Surface)
                .systemBarsPadding()
                // Any touch anywhere counts as cashier activity for the 15-minute idle lock. This
                // watches the pointer without consuming it, so taps still reach the buttons - and
                // still raise the agent's automatic user actions.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                            viewModel.markActivity()
                        }
                    }
                }
        ) {
            when (state.screen) {
                Screen.SIGN_IN -> SignInScreen(state, viewModel)
                Screen.SALE -> SaleScreen(state, viewModel)
                Screen.TENDER -> TenderScreen(state, viewModel)
                Screen.RECEIPT -> ReceiptScreen(state, viewModel)
            }
        }
    }
}
