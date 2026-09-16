// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astroshop.register.ui.theme.RegisterColors

/**
 * The register's shared chrome: the counter-sized buttons and the bar across every screen.
 *
 * Buttons are Material3 `Button`/`OutlinedButton` rather than a bare clickable box, because the
 * Dynatrace agent only raises a Compose user action from a `clickable` modifier - which those carry
 * and a `pointerInput` gesture would not.
 */
enum class RegisterButtonVariant { PRIMARY, SUBMIT, DANGER, SECONDARY }

@Composable
fun RegisterButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: RegisterButtonVariant = RegisterButtonVariant.PRIMARY,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(4.dp)
    val contentPadding = ButtonDefaults.ContentPadding
    when (variant) {
        RegisterButtonVariant.PRIMARY, RegisterButtonVariant.SUBMIT -> {
            val background =
                if (variant == RegisterButtonVariant.PRIMARY) RegisterColors.Primary else RegisterColors.Submit
            Button(
                onClick = onClick,
                enabled = enabled,
                shape = shape,
                contentPadding = contentPadding,
                colors = ButtonDefaults.buttonColors(
                    containerColor = background,
                    contentColor = Color.White,
                ),
                modifier = modifier,
            ) { ButtonLabel(label) }
        }

        RegisterButtonVariant.DANGER, RegisterButtonVariant.SECONDARY -> {
            val accent =
                if (variant == RegisterButtonVariant.DANGER) RegisterColors.Error else RegisterColors.Accent
            OutlinedButton(
                onClick = onClick,
                enabled = enabled,
                shape = shape,
                contentPadding = contentPadding,
                border = BorderStroke(2.dp, accent),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
                modifier = modifier,
            ) { ButtonLabel(label) }
        }
    }
}

@Composable
private fun ButtonLabel(label: String) {
    Text(text = label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
}

/** Which store and register this is, and who is signed in. */
@Composable
fun RegisterHeader(
    title: String,
    storeLabel: String,
    cashier: String?,
    onSignOut: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RegisterColors.Bar)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "Astro Shop Register",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Text(text = storeLabel, color = RegisterColors.MutedOnDark, fontSize = 14.sp)
        }
        Text(text = title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (cashier != null) {
                Text(text = cashier, color = RegisterColors.MutedOnDark, fontSize = 14.sp)
            }
            if (onSignOut != null) {
                OutlinedButton(
                    onClick = onSignOut,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, RegisterColors.MutedOnDark),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp,
                        vertical = 4.dp,
                    ),
                ) {
                    Text(text = "Sign out", fontSize = 14.sp)
                }
            }
        }
    }
}

/** The coloured notice blocks the sale and tender screens use. */
@Composable
fun Notice(
    borderColor: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(4.dp))
            .border(2.dp, borderColor, RoundedCornerShape(4.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** Money, always two decimals, as the counter prints it. */
fun formatUsd(amount: Double): String = "$" + String.format(java.util.Locale.US, "%.2f", amount)

