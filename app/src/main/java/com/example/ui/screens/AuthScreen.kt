package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.i18n.t
import com.example.model.AppLanguage
import com.example.ui.components.LanguageBadgeButton
import com.example.ui.components.LanguageSelectorDialog
import com.example.ui.theme.StudioColors
import com.example.viewmodel.StudioUiState
import com.example.viewmodel.StudioViewModel
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
) {
    val lang = uiState.language
    val scope = rememberCoroutineScope()

    var isSignUp by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var langDialogOpen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                LanguageBadgeButton(
                    currentLanguage = lang,
                    onClick = { langDialogOpen = true },
                )
            }

            Image(
                painter = painterResource(R.drawable.voice_pro_icon),
                contentDescription = t(lang, "app_name"),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(24.dp)),
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = t(lang, "app_name"),
                    style = MaterialTheme.typography.displaySmall,
                    color = StudioColors.TextPrimary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = t(lang, "studio_tagline"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
                border = BorderStroke(1.dp, StudioColors.Border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Sign In / Create Account segmented toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val signInColor = if (!isSignUp) StudioColors.Cyan else StudioColors.ElevatedSurface
                        val signUpColor = if (isSignUp) StudioColors.Cyan else StudioColors.ElevatedSurface
                        Surface(
                            onClick = {
                                isSignUp = false
                                errorMsg = null
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = signInColor,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("auth_mode_signin"),
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = t(lang, "auth_sign_in"),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (!isSignUp) StudioColors.BgTop else StudioColors.TextSecondary,
                                )
                            }
                        }
                        Surface(
                            onClick = {
                                isSignUp = true
                                errorMsg = null
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = signUpColor,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("auth_mode_signup"),
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = t(lang, "auth_create_account"),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (isSignUp) StudioColors.BgTop else StudioColors.TextSecondary,
                                )
                            }
                        }
                    }

                    val fieldColors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StudioColors.Cyan,
                        unfocusedBorderColor = StudioColors.Border,
                        focusedTextColor = StudioColors.TextPrimary,
                        unfocusedTextColor = StudioColors.TextPrimary,
                        focusedLabelColor = StudioColors.Cyan,
                        unfocusedLabelColor = StudioColors.TextSecondary,
                    )

                    if (isSignUp) {
                        OutlinedTextField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            singleLine = true,
                            leadingIcon = {
                                Icon(Icons.Rounded.Person, contentDescription = null, tint = StudioColors.Cyan)
                            },
                            label = {
                                Text(if (lang == AppLanguage.EN) "Display Name" else "الاسم في الاستوديو")
                            },
                            colors = fieldColors,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_name_input"),
                        )
                    }

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        leadingIcon = {
                            Icon(Icons.Rounded.Email, contentDescription = null, tint = StudioColors.Cyan)
                        },
                        label = {
                            Text(if (lang == AppLanguage.EN) "Email" else "البريد الإلكتروني")
                        },
                        colors = fieldColors,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_email_input"),
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        leadingIcon = {
                            Icon(Icons.Rounded.Lock, contentDescription = null, tint = StudioColors.Cyan)
                        },
                        label = {
                            Text(if (lang == AppLanguage.EN) "Password" else "كلمة المرور")
                        },
                        colors = fieldColors,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_password_input"),
                    )

                    if (!errorMsg.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = StudioColors.Rose.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, StudioColors.Rose.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = errorMsg!!,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = StudioColors.RoseBright,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }

                    Button(
                        onClick = {
                            if (email.isBlank() || password.length < 4) {
                                errorMsg = if (lang == AppLanguage.EN) {
                                    "Please enter a valid email and password (min 4 chars)."
                                } else {
                                    "يرجى إدخال البريد الإلكتروني وكلمة مرور صالحة (4 أحرف على الأقل)."
                                }
                                return@Button
                            }
                            isSubmitting = true
                            errorMsg = null
                            scope.launch {
                                val err = if (isSignUp) {
                                    viewModel.createAccountWithEmail(displayName, email, password)
                                } else {
                                    viewModel.signInWithEmail(email, password)
                                }
                                errorMsg = err
                                isSubmitting = false
                            }
                        },
                        enabled = !isSubmitting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioColors.Cyan,
                            contentColor = StudioColors.BgTop,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 50.dp)
                            .testTag("auth_submit_button"),
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(
                                color = StudioColors.BgTop,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = if (isSignUp) t(lang, "auth_create_account") else t(lang, "auth_email_signin"),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    OutlinedButton(
                        onClick = { viewModel.continueAsGuest() },
                        border = BorderStroke(1.dp, StudioColors.Emerald.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.Emerald),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 50.dp)
                            .testTag("auth_guest_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PersonOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = t(lang, "auth_continue_guest"),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }

    LanguageSelectorDialog(
        open = langDialogOpen,
        currentLanguage = lang,
        onSelect = { viewModel.setLanguage(it) },
        onDismiss = { langDialogOpen = false },
    )
}
