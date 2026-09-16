// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astroshop.register.ui.RegisterUiState
import com.astroshop.register.ui.RegisterViewModel
import com.astroshop.register.ui.components.Notice
import com.astroshop.register.ui.components.RegisterButton
import com.astroshop.register.ui.components.RegisterButtonVariant
import com.astroshop.register.ui.components.RegisterHeader
import com.astroshop.register.ui.theme.RegisterColors

/**
 * assistant. The cashier puts the customer's question to Astro Shop's own shopping assistant and
 * reads the answer off the counter screen.
 *
 * Deliberately four things and no more: a box to ask in, a spinner while the model thinks, the
 * answer, and a plain sentence when it does not come. There is no conversation - a queue at a
 * counter is not a chat window - so every question is asked with an empty history.
 *
 * The question arrives pre-written when an item has been scanned, because the cashier's real
 * question is almost always about the thing in their hand.
 */
@Composable
fun AssistantScreen(state: RegisterUiState, viewModel: RegisterViewModel) {
    val assistant = state.assistant

    Column(modifier = Modifier.fillMaxSize()) {
        RegisterHeader(
            title = "Ask the assistant",
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
                Column(modifier = Modifier.weight(1f)) {
                    Text("Ask about an item", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    if (assistant.productId.isNotBlank()) {
                        Text(
                            text = "About ${assistant.productId}",
                            fontSize = 13.sp,
                            color = RegisterColors.Muted,
                        )
                    }
                }
                RegisterButton(
                    label = "Back to sale",
                    variant = RegisterButtonVariant.SECONDARY,
                    onClick = viewModel::backToSaleScreen,
                )
            }

            OutlinedTextField(
                value = assistant.question,
                onValueChange = viewModel::setAssistantQuestion,
                enabled = !assistant.asking,
                placeholder = {
                    Text("What would you like to ask about the shop?", color = RegisterColors.MutedOnDark)
                },
                textStyle = TextStyle(fontSize = 18.sp),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp),
            )

            RegisterButton(
                label = if (assistant.asking) "Asking…" else "Ask",
                enabled = !assistant.asking && assistant.question.isNotBlank(),
                onClick = viewModel::askAssistant,
            )

            if (assistant.asking) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = RegisterColors.Accent)
                    Text("The assistant is looking it up…", color = RegisterColors.Muted)
                }
            }

            assistant.answer?.let { answer ->
                Notice(RegisterColors.Accent, RegisterColors.Surface) {
                    Text("Assistant", color = RegisterColors.Accent, fontWeight = FontWeight.Bold)
                    Text(text = answer, color = RegisterColors.Text, fontSize = 18.sp)
                }
            }

            assistant.error?.let { detail ->
                // A graceful failure: the counter keeps working, and the cashier is told plainly
                // that the answer is not coming rather than left watching a spinner.
                Notice(RegisterColors.Warn, RegisterColors.WarnBackground) {
                    Text(
                        text = "No answer this time",
                        color = RegisterColors.Text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    )
                    Text(text = "The question went unanswered - $detail.", color = RegisterColors.Text)
                    RegisterButton(
                        label = "Ask again",
                        variant = RegisterButtonVariant.SECONDARY,
                        onClick = viewModel::askAssistant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
