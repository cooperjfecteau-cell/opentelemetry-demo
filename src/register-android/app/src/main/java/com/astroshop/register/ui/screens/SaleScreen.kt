// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astroshop.register.config.BARCODE_SHEET
import com.astroshop.register.data.priceUsdAmount
import com.astroshop.register.ui.LookupState
import com.astroshop.register.ui.RegisterUiState
import com.astroshop.register.ui.RegisterViewModel
import com.astroshop.register.ui.components.Notice
import com.astroshop.register.ui.components.RegisterButton
import com.astroshop.register.ui.components.RegisterButtonVariant
import com.astroshop.register.ui.components.RegisterHeader
import com.astroshop.register.ui.components.formatUsd
import com.astroshop.register.ui.theme.RegisterColors

/**
 * sale. Product-id entry and the tap grid stand in for a barcode scan; the basket is the open
 * transaction. When the catalog-v2 fault runs, a failed lookup shows a banner, and three failures
 * in a row put up a blocking "Catalog unavailable" overlay for the presenter to point at. The
 * basket stays intact underneath.
 *
 * Nothing on this screen is masked. Under the Safe replay level the basket, the totals and the
 * banner are all readable, which is the whole reason Safe was chosen over the Safest default.
 */
@Composable
fun SaleScreen(state: RegisterUiState, viewModel: RegisterViewModel) {
    var entry by remember { mutableStateOf("") }
    val loading = state.lookup is LookupState.Loading

    fun runLookup(rawId: String) {
        val productId = rawId.trim().uppercase()
        if (productId.isEmpty() || loading) return
        // Keep a failed id in the box so "Retry" is one tap; a success clears it.
        entry = productId
        viewModel.scan(productId)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            RegisterHeader(
                title = "Sale",
                storeLabel = viewModel.storeLabel(),
                cashier = state.cashier,
                onSignOut = viewModel::signOut,
            )

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // The two-pane counter layout, as decided for a tablet. Narrower screens stack.
                val wide = maxWidth >= 820.dp
                if (wide) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Column(modifier = Modifier.weight(3f)) { LookupPane(state, entry, { entry = it }, ::runLookup, viewModel) }
                        Column(modifier = Modifier.weight(2f)) { BasketPane(state, viewModel) }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        LookupPane(state, entry, { entry = it }, ::runLookup, viewModel)
                        BasketPane(state, viewModel)
                    }
                }
            }
        }

        if (state.catalogDown) {
            CatalogUnavailableOverlay(
                state = state,
                loading = loading,
                onRetry = { runLookup(state.lastFailedProductId) },
                // A sale abandoned because the catalog is down is an `error` void, not a `cashier`
                // one: that distinction is what "sales lost to the fault" counts in the contract.
                onVoid = { viewModel.voidTransaction("error") },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.LookupPane(
    state: RegisterUiState,
    entry: String,
    onEntryChange: (String) -> Unit,
    onLookup: (String) -> Unit,
    viewModel: RegisterViewModel,
) {
    val loading = state.lookup is LookupState.Loading

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The two counter tasks that are not a sale (bluebox-demo#51). Both open from here and
        // come back here, leaving the basket exactly as it was.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Scan or enter product id",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            RegisterButton(
                label = "Order pickup",
                variant = RegisterButtonVariant.SECONDARY,
                onClick = viewModel::openPickup,
            )
            RegisterButton(
                label = "Ask",
                variant = RegisterButtonVariant.SECONDARY,
                onClick = viewModel::openAssistant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = entry,
                onValueChange = { onEntryChange(it.uppercase()) },
                singleLine = true,
                placeholder = { Text("e.g. 66VCHSJNUP", color = RegisterColors.MutedOnDark) },
                textStyle = TextStyle(fontSize = 20.sp, letterSpacing = 1.sp),
                shape = RoundedCornerShape(4.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { onLookup(entry) }),
                modifier = Modifier.weight(1f),
            )
            RegisterButton(
                label = if (loading) "Looking up…" else "Look up",
                enabled = !loading && entry.isNotBlank(),
                onClick = { onLookup(entry) },
            )
        }

        when (val lookup = state.lookup) {
            is LookupState.Added -> Notice(RegisterColors.Primary, RegisterColors.OkBackground) {
                Text("Added ${lookup.name}", color = RegisterColors.Text)
            }

            is LookupState.NotFound -> Notice(RegisterColors.Warn, RegisterColors.WarnBackground) {
                Text("No product ${lookup.productId}", color = RegisterColors.Text, fontWeight = FontWeight.Bold)
                Text("Check the id and scan again.", color = RegisterColors.Text)
            }

            is LookupState.CartError -> Notice(RegisterColors.Warn, RegisterColors.WarnBackground) {
                Text("Could not add ${lookup.name}", color = RegisterColors.Text, fontWeight = FontWeight.Bold)
                Text(
                    "The order did not accept the item (HTTP ${statusLabel(lookup.status)}). Scan it again.",
                    color = RegisterColors.Text,
                )
            }

            is LookupState.Failed -> Notice(RegisterColors.Error, RegisterColors.ErrorBackground) {
                Text(
                    "Product lookup failed",
                    color = RegisterColors.Error,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                )
                Text(
                    "The catalog did not respond for ${lookup.productId} " +
                        "(HTTP ${statusLabel(lookup.status)}). The item was not added.",
                    color = RegisterColors.Text,
                )
                if (state.lookupFailures > 1) {
                    Text(
                        "${state.lookupFailures} lookups have failed in a row. " +
                            "Call a manager if this keeps happening.",
                        color = RegisterColors.Text,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    RegisterButton(
                        label = "Retry",
                        variant = RegisterButtonVariant.DANGER,
                        onClick = { onLookup(lookup.productId) },
                    )
                    RegisterButton(
                        label = "Dismiss",
                        variant = RegisterButtonVariant.SECONDARY,
                        onClick = viewModel::dismissLookupNotice,
                    )
                }
            }

            else -> Unit
        }

        Text(
            text = "Quick scan (stand-in for a barcode sheet)",
            color = RegisterColors.Muted,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BARCODE_SHEET.forEach { product ->
                Column(
                    modifier = Modifier
                        .width(180.dp)
                        .border(1.dp, RegisterColors.Accent, RoundedCornerShape(4.dp))
                        .clickable(enabled = !loading) { onLookup(product.id) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = product.id,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = product.name,
                        fontSize = 12.sp,
                        color = RegisterColors.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun BasketPane(state: RegisterUiState, viewModel: RegisterViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 320.dp)
            .border(1.dp, RegisterColors.Accent, RoundedCornerShape(4.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Transaction", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            if (state.basket.isEmpty()) {
                Text(
                    text = "Scan an item to start a sale.",
                    color = RegisterColors.Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp),
                )
            } else {
                state.basket.forEach { line ->
                    val unitPrice = line.product.priceUsdAmount()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = line.product.name.orEmpty(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${line.product.id} · ${formatUsd(unitPrice)} each",
                                fontSize = 12.sp,
                                color = RegisterColors.Muted,
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            QuantityButton("−") { viewModel.changeQuantity(line.product.id!!, -1) }
                            Text(
                                text = line.quantity.toString(),
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(24.dp),
                            )
                            QuantityButton("+") { viewModel.changeQuantity(line.product.id!!, 1) }
                        }
                        Text(
                            text = formatUsd(unitPrice * line.quantity),
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(90.dp),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text("${state.itemCount} item${if (state.itemCount == 1) "" else "s"}")
            Text("Total ${formatUsd(state.total)}", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            RegisterButton(
                label = "Void transaction",
                variant = RegisterButtonVariant.DANGER,
                enabled = state.transactionOpen,
                onClick = { viewModel.voidTransaction("cashier") },
                modifier = Modifier.weight(1f),
            )
            RegisterButton(
                label = "Tender ${formatUsd(state.total)}",
                enabled = state.basket.isNotEmpty(),
                onClick = viewModel::goToTender,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun QuantityButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .border(1.dp, RegisterColors.Muted, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** The blocking overlay a presenter can point at. The transaction underneath is untouched. */
@Composable
private fun CatalogUnavailableOverlay(
    state: RegisterUiState,
    loading: Boolean,
    onRetry: () -> Unit,
    onVoid: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xD911181C))
            // Swallows every tap, which is what makes the overlay blocking.
            .clickable(enabled = true, onClick = {})
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .background(RegisterColors.ErrorBackground, RoundedCornerShape(6.dp))
                .border(3.dp, RegisterColors.Error, RoundedCornerShape(6.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Catalog unavailable",
                color = RegisterColors.Error,
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
            )
            Text(
                text = "Product lookup has failed ${state.lookupFailures} times in a row. Items cannot be " +
                    "added until the catalog responds. The current transaction is kept.",
                color = RegisterColors.Text,
                fontSize = 18.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RegisterButton(
                    label = if (loading) "Trying…" else "Try again",
                    variant = RegisterButtonVariant.SUBMIT,
                    enabled = !loading,
                    onClick = onRetry,
                    modifier = Modifier.weight(1f),
                )
                RegisterButton(
                    label = "Void transaction",
                    variant = RegisterButtonVariant.DANGER,
                    onClick = onVoid,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 0 means the call never reached a server, which reads better as "error" at the counter. */
private fun statusLabel(status: Int): String = if (status == 0) "error" else status.toString()
