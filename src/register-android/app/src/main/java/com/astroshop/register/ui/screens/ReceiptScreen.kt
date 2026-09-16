// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astroshop.register.config.RegisterConfig
import com.astroshop.register.data.priceUsdAmount
import com.astroshop.register.ui.RegisterUiState
import com.astroshop.register.ui.RegisterViewModel
import com.astroshop.register.ui.TenderType
import com.astroshop.register.ui.components.RegisterButton
import com.astroshop.register.ui.components.RegisterHeader
import com.astroshop.register.ui.components.formatUsd
import com.astroshop.register.ui.theme.RegisterColors
import java.text.DateFormat
import java.util.Date

/** receipt. On-screen only; no printing or emailing. The order id is Astro Shop's, from checkout. */
@Composable
fun ReceiptScreen(state: RegisterUiState, viewModel: RegisterViewModel) {
    val receipt = state.lastReceipt ?: return
    val config = RegisterConfig.get()

    Column(modifier = Modifier.fillMaxSize()) {
        RegisterHeader(title = "Receipt", storeLabel = viewModel.storeLabel(), cashier = state.cashier)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .width(420.dp)
                        .border(1.dp, RegisterColors.Accent, RoundedCornerShape(4.dp))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Centered("Astro Shop · ${config.storeName}", fontSize = 22.sp, bold = true)
                    Centered(
                        "Store ${config.storeId} · Register ${config.registerId} · ${receipt.cashier}",
                        fontSize = 14.sp,
                        color = RegisterColors.Muted,
                    )
                    Centered(
                        DateFormat.getDateTimeInstance().format(Date(receipt.completedAtMillis)),
                        fontSize = 14.sp,
                        color = RegisterColors.Muted,
                    )

                    Rule()
                    receipt.items.forEach { line ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "${line.quantity} × ${line.product.name.orEmpty()}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(formatUsd(line.product.priceUsdAmount() * line.quantity))
                        }
                    }
                    Rule()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Total", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(formatUsd(receipt.total), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(if (receipt.tenderType == TenderType.CARD) "Card" else "Cash")
                        Text(formatUsd(receipt.tendered))
                    }
                    if (receipt.tenderType == TenderType.CASH) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Change")
                            Text(formatUsd(receipt.change))
                        }
                    }

                    Rule()
                    Centered("Order id", fontSize = 14.sp, color = RegisterColors.Muted)
                    Centered(receipt.orderId, fontSize = 13.sp, mono = true)
                }

                RegisterButton(
                    label = "New sale",
                    onClick = viewModel::newSale,
                    modifier = Modifier.width(420.dp),
                )
            }
        }
    }
}

@Composable
private fun Centered(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    bold: Boolean = false,
    mono: Boolean = false,
    color: androidx.compose.ui.graphics.Color = RegisterColors.Text,
) {
    Text(
        text = text,
        fontSize = fontSize,
        color = color,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Rule() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 10.dp),
        thickness = 1.dp,
        color = RegisterColors.Muted,
    )
}
