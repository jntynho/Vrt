package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.*
import com.example.R
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalVaultPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class PasscodeDialogMode {
    SETUP_NEW,        // Step 1: Enter new passcode, Step 2: Confirm new passcode
    VERIFY_TO_DISABLE,// Verify current passcode before turning off
    CHANGE_OLD,       // Verify current passcode before allowing change
    CHANGE_NEW        // Enter and confirm new passcode
}

/**
 * Aesthetic Dialog for setting up, confirming, changing or disabling
 * the 4-digit numeric passcode.
 */
@Composable
fun PasscodeSetupDialog(
    mode: PasscodeDialogMode,
    storedHash: String,
    onSuccess: (newPasscode: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalVaultPalette.current
    val accentColor = LocalAccentColor.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    // Steps state
    var currentStep by remember {
        mutableStateOf(
            when (mode) {
                PasscodeDialogMode.SETUP_NEW -> "ENTER_NEW"
                PasscodeDialogMode.VERIFY_TO_DISABLE -> "VERIFY_CURRENT"
                PasscodeDialogMode.CHANGE_OLD -> "VERIFY_CURRENT_FOR_CHANGE"
                PasscodeDialogMode.CHANGE_NEW -> "ENTER_NEW"
            }
        )
    }

    var firstEnteredPin by remember { mutableStateOf("") }
    var currentPinInput by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val shakeOffset = remember { Animatable(0f) }

    fun triggerShake(msg: String) {
        coroutineScope.launch {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            isError = true
            statusMessage = msg
            shakeOffset.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 350
                    0f at 0
                    -20f at 50
                    20f at 100
                    -15f at 150
                    15f at 200
                    -8f at 250
                    8f at 300
                    0f at 350
                }
            )
            delay(1200L)
            currentPinInput = ""
            isError = false
            statusMessage = null
        }
    }

    fun handlePinComplete(pin: String) {
        when (currentStep) {
            "VERIFY_CURRENT" -> {
                if (com.example.util.PasscodeManager.verifyPasscode(pin, storedHash)) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSuccess(null) // Disabling
                } else {
                    triggerShake("Incorrect Current Passcode")
                }
            }
            "VERIFY_CURRENT_FOR_CHANGE" -> {
                if (com.example.util.PasscodeManager.verifyPasscode(pin, storedHash)) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    currentPinInput = ""
                    currentStep = "ENTER_NEW"
                } else {
                    triggerShake("Incorrect Current Passcode")
                }
            }
            "ENTER_NEW" -> {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                firstEnteredPin = pin
                currentPinInput = ""
                currentStep = "CONFIRM_NEW"
            }
            "CONFIRM_NEW" -> {
                if (pin == firstEnteredPin) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSuccess(pin)
                } else {
                    triggerShake("Passcodes don't match. Try again.")
                    firstEnteredPin = ""
                    currentStep = "ENTER_NEW"
                }
            }
        }
    }

    fun onDigit(digit: String) {
        if (currentPinInput.length < 4 && !isError) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            val updated = currentPinInput + digit
            currentPinInput = updated
            if (updated.length == 4) {
                handlePinComplete(updated)
            }
        }
    }

    fun onDel() {
        if (currentPinInput.isNotEmpty() && !isError) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentPinInput = currentPinInput.dropLast(1)
            statusMessage = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val isDarkTheme = palette.name != "Light"
        val closeBtnBg = if (isDarkTheme) Color(0xFF353638) else Color(0xFFE4E6EB)
        val keypadButtonBg = if (isDarkTheme) Color(0xFF353638) else Color(0xFFE4E6EB)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = VaultDialogShape,
                color = palette.dialogBg,
                modifier = Modifier
                    .widthIn(max = 345.dp)
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .vaultTopGlow(cornerRadius = 26.dp)
                    .testTag("passcode_dialog")
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 22.dp, vertical = 22.dp)
                        .offset(x = shakeOffset.value.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                // Header Row matching app dialog language
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when (currentStep) {
                            "VERIFY_CURRENT", "VERIFY_CURRENT_FOR_CHANGE" -> "Verify Passcode"
                            "ENTER_NEW" -> "Set New Passcode"
                            "CONFIRM_NEW" -> "Confirm Passcode"
                            else -> "App Security"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(closeBtnBg)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onDismiss
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = palette.textPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = statusMessage ?: when (currentStep) {
                        "VERIFY_CURRENT" -> "Enter your current 4-digit code to disable protection"
                        "VERIFY_CURRENT_FOR_CHANGE" -> "Enter your current 4-digit code to proceed"
                        "ENTER_NEW" -> "Choose a 4-digit security code for the app"
                        "CONFIRM_NEW" -> "Re-enter the 4-digit security code to confirm"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isError) MaterialTheme.colorScheme.error else palette.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )

                Spacer(modifier = Modifier.height(26.dp))

                // Pin Dots matching AppLockScreen aesthetic
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 0 until 4) {
                        val isFilled = i < currentPinInput.length
                        val dotScale by animateFloatAsState(
                            targetValue = if (isFilled) 1.15f else 1.0f,
                            animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
                            label = "DialogDotScale_$i"
                        )
                        val dotColor by animateColorAsState(
                            targetValue = when {
                                isError -> MaterialTheme.colorScheme.error
                                isFilled -> accentColor
                                else -> palette.cardBg.copy(alpha = 0.5f)
                            },
                            animationSpec = tween(180),
                            label = "DialogDotColor_$i"
                        )
                        val dotBorderColor by animateColorAsState(
                            targetValue = when {
                                isError -> MaterialTheme.colorScheme.error
                                isFilled -> accentColor
                                else -> palette.border.copy(alpha = 0.6f)
                            },
                            animationSpec = tween(180),
                            label = "DialogDotBorder_$i"
                        )

                        Box(
                            modifier = Modifier
                                .size((16 * dotScale).dp)
                                .clip(CircleShape)
                                .background(dotColor)
                                .border(
                                    BorderStroke(1.5.dp, dotBorderColor),
                                    shape = CircleShape
                                )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Keypad matching AppLockScreen circular button style
                val rows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("", "0", "DEL")
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    for (row in rows) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (btn in row) {
                                when (btn) {
                                    "" -> Spacer(modifier = Modifier.size(68.dp))
                                    "DEL" -> {
                                        Surface(
                                            modifier = Modifier
                                                .size(68.dp)
                                                .clip(CircleShape)
                                                .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = ripple(bounded = true)
                                            ) { onDel() },
                                            shape = CircleShape,
                                            color = keypadButtonBg
                                        ) {
                                            Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_lock_backspace),
                                                    contentDescription = "Backspace",
                                                    tint = palette.textPrimary,
                                                    modifier = Modifier.size(26.dp)
                                                )
                                            }
                                        }
                                    }
                                    else -> {
                                        Surface(
                                            modifier = Modifier
                                                .size(68.dp)
                                                .clip(CircleShape)
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = ripple(bounded = true)
                                                ) { onDigit(btn) }
                                                .testTag("dialog_keypad_$btn"),
                                            shape = CircleShape,
                                            color = keypadButtonBg
                                        ) {
                                            Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = btn,
                                                    style = MaterialTheme.typography.headlineMedium.copy(
                                                        fontSize = 24.sp,
                                                        platformStyle = PlatformTextStyle(
                                                            includeFontPadding = false
                                                        ),
                                                        lineHeightStyle = LineHeightStyle(
                                                            alignment = LineHeightStyle.Alignment.Center,
                                                            trim = LineHeightStyle.Trim.Both
                                                        )
                                                    ),
                                                    fontWeight = FontWeight.SemiBold,
                                                    textAlign = TextAlign.Center,
                                                    color = palette.textPrimary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

