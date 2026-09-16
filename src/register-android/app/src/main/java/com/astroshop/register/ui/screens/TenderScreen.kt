// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astroshop.register.ui.RegisterUiState
import com.astroshop.register.ui.RegisterViewModel
import com.astroshop.register.ui.TenderType
import com.astroshop.register.ui.components.Notice
import com.astroshop.register.ui.components.RegisterButton
import com.astroshop.register.ui.components.RegisterButtonVariant
import com.astroshop.register.ui.components.RegisterHeader
import com.astroshop.register.ui.components.formatUsd
import com.astroshop.register.ui.theme.RegisterColors
import kotlin.math.max

/**
 * tender. Card or cash, and the amount. No card details are collected here: whichever tender the
 * cashier picks, the order goes to checkout with the store's house card, and the only thing
 * reported is which tender it was - and that only implicitly, through the sale's own events.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TenderScreen(state: RegisterUiState, viewModel: RegisterViewModel) {
    var type by remember { mutableStateOf(TenderType.CARD) }
    var cash by remember { mutableStateOf("") }

    val total = state.total
    val cashReceived = cash.toDoubleOrNull() ?: 0.0
    val tendered = if (type == TenderType.CARD) total else cashReceived
    // Half a cent of slack, so an "Exact" tap is never a penny short of its own total.
    val canComplete = tendered + 0.005 >= total && !state.charging
    val quickCash = (listOf(total) + listOf(20, 50, 100, 500, 1000, 5000)
        .map(Int::toDouble)
        .filter { it > total }).take(4)

    Column(modifier = Modifier.fillMaxSize()) {
        RegisterHeader(title = "Tender", storeLabel = viewModel.storeLabel(), cashier = state.cashier)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.width(440.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "${state.itemCount} item${if (state.itemCount == 1) "" else "s"} · amount due",
                    color = RegisterColors.Muted,
                )
                Text(text = formatUsd(total), fontSize = 48.sp, fontWeight = FontWeight.Bold)

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    TenderType.entries.forEach { option ->
                        val selected = type == option
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (selected) RegisterColors.Accent else Color.Transparent,
                                    RoundedCornerShape(4.dp),
                                )
                                .border(2.dp, RegisterColors.Accent, RoundedCornerShape(4.dp))
                                .clickable { type = option }
                                .padding(vertical = 18.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (option == TenderType.CARD) "Card" else "Cash",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (selected) Color.White else RegisterColors.Text,
                            )
                        }
                    }
                }

                if (type == TenderType.CARD) {
                    Text(
                        text = "Charge ${formatUsd(total)} to the customer's card.",
                        color = RegisterColors.Muted,
                    )
                } else {
                    Text("Cash received", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = cash,
                        onValueChange = { input -> cash = input.filter { it.isDigit() || it == '.' } },
                        singleLine = true,
                        placeholder = { Text("0.00", color = RegisterColors.MutedOnDark) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = TextStyle(fontSize = 24.sp),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        quickCash.forEachIndexed { index, amount ->
                            Box(
                                modifier = Modifier
                                    .border(1.dp, RegisterColors.Muted, RoundedCornerShape(4.dp))
                                    .clickable { cash = String.format(java.util.Locale.US, "%.2f", amount) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                Text(if (index == 0) "Exact" else formatUsd(amount))
                            }
                        }
                    }
                    Text(
                        text = "Change due ${formatUsd(max(0.0, cashReceived - total))}",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                if (state.chargeFailedStatus != null) {
                    Notice(RegisterColors.Error, RegisterColors.ErrorBackground) {
                        Text(
                            text = "Sale not completed",
                            color = RegisterColors.Error,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        )
                        Text(
                            text = "Astro Shop did not accept the order (HTTP " +
                                "${if (state.chargeFailedStatus == 0) "error" else state.chargeFailedStatus}). " +
                                "Try again, or void the transaction.",
                            color = RegisterColors.Text,
                        )
                        RegisterButton(
                            label = "Void transaction",
                            variant = RegisterButtonVariant.DANGER,
                            // `error`, not `cashier`: the sale was lost to a failure, not abandoned.
                            onClick = { viewModel.voidTransaction("error") },
                        )
                    }
                }

                RegisterButton(
                    label = when {
                        state.charging -> "Completing…"
                        type == TenderType.CARD -> "Charge ${formatUsd(total)}"
                        else -> "Complete sale"
                    },
                    enabled = canComplete,
                    onClick = { viewModel.tender(type, tendered) },
                    modifier = Modifier.fillMaxWidth(),
                )
                RegisterButton(
                    label = "Back to sale",
                    variant = RegisterButtonVariant.SECONDARY,
                    enabled = !state.charging,
                    onClick = viewModel::backToSale,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
