// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import com.astroshop.register.config.RegisterConfig
import com.astroshop.register.ui.RegisterUiState
import com.astroshop.register.ui.RegisterViewModel
import com.astroshop.register.ui.components.RegisterButton
import com.astroshop.register.ui.components.RegisterButtonVariant
import com.astroshop.register.ui.components.RegisterHeader
import com.astroshop.register.ui.theme.RegisterColors
import com.dynatrace.agent.compose.api.dtMask

private const val PIN_LENGTH = 4
private val KEYS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "Clear", "0", "⌫")

/**
 * sign_in. Cashier number plus a 4-digit PIN on an on-screen keypad. Any 4-digit PIN is accepted;
 * there is no auth behind it, and the PIN never leaves this screen.
 *
 * Masking: the replay level is Safe, which masks editable fields but leaves labels readable. That
 * is right everywhere else in the app and wrong here, so the cashier-id field and the whole PIN
 * block carry `dtMask` explicitly. Compose has no documented unmask, so anything inside a masked
 * subtree stays masked - which is why the mask is drawn tightly around the keypad and not around
 * the card.
 */
@Composable
fun SignInScreen(state: RegisterUiState, viewModel: RegisterViewModel) {
    var cashierNumber by remember { mutableStateOf(RegisterConfig.get().defaultCashierNumber) }
    var pin by remember { mutableStateOf("") }

    fun press(key: String) {
        pin = when (key) {
            "Clear" -> ""
            "⌫" -> pin.dropLast(1)
            else -> if (pin.length < PIN_LENGTH) pin + key else pin
        }
    }

    val canSignIn = cashierNumber.isNotBlank() && pin.length == PIN_LENGTH

    Column(modifier = Modifier.fillMaxSize()) {
        RegisterHeader(title = "Sign in", storeLabel = viewModel.storeLabel(), cashier = null)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.width(360.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Cashier sign-in", fontSize = 22.sp, fontWeight = FontWeight.Bold)

                Text("Cashier number", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = cashierNumber,
                    onValueChange = { cashierNumber = it.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    textStyle = TextStyle(fontSize = 20.sp),
                    shape = RoundedCornerShape(4.dp),
                    // Contract: the cashier id is masked in replay even though it is only
                    // pseudonymous, so a shoulder-surfed replay cannot be tied back to a person.
                    modifier = Modifier
                        .fillMaxWidth()
                        .dtMask(),
                )

                Text("PIN", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))

                // Everything that could reveal the PIN - the filled dots and every key label -
                // lives inside this one masked subtree.
                Column(
                    modifier = Modifier.dtMask(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    ) {
                        repeat(PIN_LENGTH) { index ->
                            val filled = index < pin.length
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .background(
                                        if (filled) RegisterColors.Accent else Color.Transparent,
                                        CircleShape,
                                    )
                                    .border(
                                        2.dp,
                                        if (filled) RegisterColors.Accent else RegisterColors.Muted,
                                        CircleShape,
                                    )
                            )
                        }
                    }

                    // A three-column grid built from Rows: LazyVerticalGrid inside a Column needs a
                    // bounded height, and a fixed 4x3 pad does not need laziness.
                    KEYS.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { key ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .border(1.dp, RegisterColors.Muted, RoundedCornerShape(4.dp))
                                        // clickable, not pointerInput: the agent raises a Compose
                                        // user action only from clickable.
                                        .clickable { press(key) }
                                        .padding(vertical = 14.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(text = key, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                RegisterButton(
                    label = "Sign in",
                    variant = RegisterButtonVariant.SUBMIT,
                    enabled = canSignIn,
                    onClick = {
                        viewModel.signIn(cashierNumber.trim())
                        pin = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Demo register: any 4-digit PIN signs in.",
                    fontSize = 13.sp,
                    color = RegisterColors.Muted,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
