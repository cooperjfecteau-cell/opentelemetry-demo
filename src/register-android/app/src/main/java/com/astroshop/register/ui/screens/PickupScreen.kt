// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astroshop.register.data.PickupOrder
import com.astroshop.register.data.countedItems
import com.astroshop.register.ui.PickupState
import com.astroshop.register.ui.RegisterUiState
import com.astroshop.register.ui.RegisterViewModel
import com.astroshop.register.ui.components.Notice
import com.astroshop.register.ui.components.RegisterButton
import com.astroshop.register.ui.components.RegisterButtonVariant
import com.astroshop.register.ui.components.RegisterHeader
import com.astroshop.register.ui.components.formatUsd
import com.astroshop.register.ui.theme.RegisterColors

/**
 * pickup. A cashier hands over an order the customer bought online, which is the half of the
 * journey the shop's own site cannot show: one order, placed on the web, collected by a person.
 *
 * Three states the cashier moves through - the queue of orders waiting at this store, one order's
 * contents, and the handover - served by the order-pickup API (bluebox-demo#50). The sale on the
 * other screen is untouched throughout: a pickup interrupts a sale, it does not end it.
 *
 * Nothing here is masked. The customer name is the only field that reads like personal data, and it
 * is not: these orders are synthetic and the names are invented.
 */
@Composable
fun PickupScreen(state: RegisterUiState, viewModel: RegisterViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        RegisterHeader(
            title = "Order pickup",
            storeLabel = viewModel.storeLabel(),
            cashier = state.cashier,
            onSignOut = viewModel::signOut,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Online orders",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                RegisterButton(
                    label = "Back to sale",
                    variant = RegisterButtonVariant.SECONDARY,
                    onClick = viewModel::backToSaleScreen,
                )
            }

            when (val pickup = state.pickup) {
                is PickupState.LoadingOrders -> Waiting("Loading the orders waiting at this store…")

                is PickupState.Orders -> OrderQueue(pickup.orders, viewModel)

                is PickupState.LoadingOrder -> Waiting("Opening the order…")

                is PickupState.Order -> OrderDetail(pickup, viewModel)

                is PickupState.Collected -> Collected(pickup, viewModel)

                is PickupState.Failed -> Notice(RegisterColors.Error, RegisterColors.ErrorBackground) {
                    Text(
                        text = pickup.message,
                        color = RegisterColors.Error,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    )
                    Text(
                        text = "The shop answered HTTP ${if (pickup.status == 0) "error" else pickup.status}.",
                        color = RegisterColors.Text,
                    )
                    RegisterButton(
                        label = "Try again",
                        variant = RegisterButtonVariant.DANGER,
                        onClick = viewModel::loadReadyOrders,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                is PickupState.Idle -> Unit
            }
        }
    }
}

/** The queue. One row per order, and the row is the button: a tap opens it. */
@Composable
private fun OrderQueue(orders: List<PickupOrder>, viewModel: RegisterViewModel) {
    if (orders.isEmpty()) {
        Notice(RegisterColors.Warn, RegisterColors.WarnBackground) {
            Text("No orders are waiting", color = RegisterColors.Text, fontWeight = FontWeight.Bold)
            Text("Nothing has been picked and made ready at this store yet.", color = RegisterColors.Text)
        }
        RegisterButton(
            label = "Refresh",
            variant = RegisterButtonVariant.SECONDARY,
            onClick = viewModel::loadReadyOrders,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        orders.forEach { order ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, RegisterColors.Accent, RoundedCornerShape(4.dp))
                    .clickable { viewModel.selectPickupOrder(order.orderId.orEmpty()) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = order.customerName.orEmpty(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Order ${shortOrderId(order.orderId)} · placed ${shortTime(order.placedAt)}",
                        fontSize = 13.sp,
                        color = RegisterColors.Muted,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    text = "${order.countedItems()} item${if (order.countedItems() == 1) "" else "s"}",
                    color = RegisterColors.Muted,
                )
                Text(
                    text = formatUsd(order.total ?: 0.0),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(100.dp),
                )
            }
        }

        RegisterButton(
            label = "Refresh",
            variant = RegisterButtonVariant.SECONDARY,
            onClick = viewModel::loadReadyOrders,
        )
    }
}

/** One order, checked against the bag, then handed over. */
@Composable
private fun OrderDetail(pickup: PickupState.Order, viewModel: RegisterViewModel) {
    val order = pickup.order
    val unknown = order.status == "unknown" || order.orderId.isNullOrBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, RegisterColors.Accent, RoundedCornerShape(4.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (unknown) {
            Notice(RegisterColors.Warn, RegisterColors.WarnBackground) {
                Text("The shop does not know this order", color = RegisterColors.Text, fontWeight = FontWeight.Bold)
                Text("It may already have been collected. Ask the customer for their order email.", color = RegisterColors.Text)
            }
        } else {
            Text(order.customerName.orEmpty(), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "Order ${order.orderId} · placed ${shortTime(order.placedAt)}",
                fontSize = 13.sp,
                color = RegisterColors.Muted,
                fontFamily = FontFamily.Monospace,
            )

            order.items.orEmpty().forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = "${item.productId} · ${formatUsd(item.price ?: 0.0)} each",
                            fontSize = 12.sp,
                            color = RegisterColors.Muted,
                        )
                    }
                    Text("× ${item.quantity ?: 0}", fontWeight = FontWeight.Bold)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text("${order.countedItems()} item${if (order.countedItems() == 1) "" else "s"}")
                Text(
                    text = "Paid online ${formatUsd(order.total ?: 0.0)}",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (pickup.error != null) {
            Notice(RegisterColors.Error, RegisterColors.ErrorBackground) {
                Text("Collection was not recorded", color = RegisterColors.Error, fontWeight = FontWeight.Bold)
                Text(pickup.error, color = RegisterColors.Text)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            RegisterButton(
                label = "Back to orders",
                variant = RegisterButtonVariant.SECONDARY,
                onClick = viewModel::loadReadyOrders,
                modifier = Modifier.weight(1f),
            )
            RegisterButton(
                // The money was taken online, so this is a handover, not a tender.
                label = if (pickup.collecting) "Recording…" else "Mark collected",
                variant = RegisterButtonVariant.SUBMIT,
                enabled = !pickup.collecting && !unknown,
                onClick = viewModel::collectPickupOrder,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The end of a pickup: the shop has it, and the cashier can take the next one or go back. */
@Composable
private fun Collected(pickup: PickupState.Collected, viewModel: RegisterViewModel) {
    Notice(RegisterColors.Primary, RegisterColors.OkBackground) {
        Text(
            text = "Collected",
            color = RegisterColors.Primary,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
        )
        Text(
            text = "${pickup.order.customerName.orEmpty()} · order ${pickup.order.orderId}",
            color = RegisterColors.Text,
        )
        if (!pickup.collectedAt.isNullOrBlank()) {
            Text(text = "Recorded at ${shortTime(pickup.collectedAt)}", color = RegisterColors.Muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            RegisterButton(label = "Next order", onClick = viewModel::loadReadyOrders)
            RegisterButton(
                label = "Back to sale",
                variant = RegisterButtonVariant.SECONDARY,
                onClick = viewModel::backToSaleScreen,
            )
        }
    }
}

/** The spinner, which is all a cashier needs while the shop is being asked. */
@Composable
private fun Waiting(message: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = RegisterColors.Accent)
        Text(text = message, color = RegisterColors.Muted)
    }
}

/**
 * Enough of an order id to read out loud without filling the row - the **leading** characters, not
 * the trailing ones. Astro Shop's order ids are version-1 UUIDs, so every order placed on the same
 * host ends in the same node field: a queue shortened from the right shows five identical ids.
 */
private fun shortOrderId(orderId: String?): String =
    orderId.orEmpty().take(8).ifBlank { "unknown" }

/**
 * The API's timestamps are ISO-8601 strings. Rendered as month, day and time, because a counter
 * cares when an order was placed, not what year it is. Anything else is shown as it arrived.
 */
private fun shortTime(raw: String?): String {
    val value = raw.orEmpty()
    if (value.length < 16 || value[10] != 'T') return value
    return value.substring(5, 16).replace('T', ' ')
}
