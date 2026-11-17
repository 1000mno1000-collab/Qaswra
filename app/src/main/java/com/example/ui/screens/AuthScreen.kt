package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.example.i18n.t
import com.example.model.AppLanguage
import com.example.ui.components.LanguageBadgeButton
import com.example.ui.components.LanguageSelectorDialog
import com.example.ui.theme.StudioColors
import com.example.ui.theme.VoiceProTheme
import com.example.viewmodel.StudioUiState
import com.example.viewmodel.StudioViewModel
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun AuthScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
) {
    val lang = uiState.language
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    val scope = rememberCoroutineScope()
    val defaultWebClientId = if (isPreview) {
        ""
    } else {
        stringResource(R.string.default_web_client_id).trim()
    }

    var isSignUp by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var isGoogleLoading by remember { mutableStateOf(false) }
    var langDialogOpen by remember { mutableStateOf(false) }

    AuthScreenContent(
        lang = lang,
        isSignUp = isSignUp,
        displayName = displayName,
        email = email,
        password = password,
        errorMsg = errorMsg,
        isSubmitting = isSubmitting,
        isGoogleLoading = isGoogleLoading,
        onOpenLanguageDialog = { langDialogOpen = true },
        onSelectMode = { signUp ->
            isSignUp = signUp
            errorMsg = null
        },
        onDisplayNameChange = { displayName = it },
        onEmailChange = { email = it },
        onPasswordChange = { password = it },
        onGoogleSignIn = {
            if (isSubmitting || isGoogleLoading) return@AuthScreenContent
            if (defaultWebClientId.isBlank()) {
                errorMsg = if (lang == AppLanguage.EN) {
                    "Google Sign-In client configuration is unavailable."
                } else {
                    "معرّف عميل Google غير متوفر."
                }
                return@AuthScreenContent
            }
            val activity = context.findActivity()
            if (activity == null) {
                errorMsg = if (lang == AppLanguage.EN) {
                    "Unable to start Google Sign-In from this context."
                } else {
                    "تعذر بدء تسجيل الدخول عبر Google من هذه الواجهة."
                }
                return@AuthScreenContent
            }

            isGoogleLoading = true
            errorMsg = null
            scope.launch {
                try {
                    val credentialManager = CredentialManager.create(activity)
                    val signInOption = GetSignInWithGoogleOption.Builder(
                        serverClientId = defaultWebClientId,
                    ).build()
                    val request = GetCredentialRequest.Builder()
                        .addCredentialOption(signInOption)
                        .build()
                    val result = credentialManager.getCredential(
                        context = activity,
                        request = request,
                    )
                    val credential = result.credential
                    if (credential is CustomCredential &&
                        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                    ) {
                        val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
                        val firebaseCredential = GoogleAuthProvider.getCredential(googleCred.idToken, null)
                        val authResult = Firebase.auth.signInWithCredential(firebaseCredential).await()
                        val user = authResult.user
                        if (user != null) {
                            viewModel.completeGoogleSignIn(
                                uid = user.uid,
                                displayName = user.displayName ?: googleCred.displayName.orEmpty(),
                                email = user.email ?: googleCred.id,
                            )
                        } else {
                            errorMsg = if (lang == AppLanguage.EN) {
                                "Google Sign-In did not return a user session."
                            } else {
                                "لم يتم إرجاع جلسة مستخدم صالحة من Google."
                            }
                        }
                    } else {
                        errorMsg = if (lang == AppLanguage.EN) {
                            "Unexpected credential type returned."
                        } else {
                            "تم استلام نوع بيانات اعتماد غير متوقع."
                        }
                    }
                } catch (e: GetCredentialCancellationException) {
                    Log.w("Auth", "Google Sign-In cancelled or dismissed: ${e.message}", e)
                } catch (e: NoCredentialException) {
                    Log.w("Auth", "No Google credentials found on device: ${e.message}", e)
                    errorMsg = if (lang == AppLanguage.EN) {
                        "No Google account is signed in on this device."
                    } else {
                        "لا يوجد حساب Google مسجّل على هذا الجهاز."
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("Auth", "Google Sign-In failed", e)
                    errorMsg = e.localizedMessage ?: if (lang == AppLanguage.EN) {
                        "Google Sign-In failed. Please try again."
                    } else {
                        "فشل تسجيل الدخول بحساب Google، يرجى المحاولة مرة أخرى."
                    }
                } finally {
                    isGoogleLoading = false
                }
            }
        },
        onSubmitEmail = {
            if (isSubmitting || isGoogleLoading) return@AuthScreenContent
            if (email.isBlank() || password.length < 4) {
                errorMsg = if (lang == AppLanguage.EN) {
                    "Please enter a valid email and password (min 4 chars)."
                } else {
                    "يرجى إدخال البريد الإلكتروني وكلمة مرور صالحة (4 أحرف على الأقل)."
                }
                return@AuthScreenContent
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
        onContinueAsGuest = { viewModel.continueAsGuest() },
    )

    LanguageSelectorDialog(
        open = langDialogOpen,
        currentLanguage = lang,
        onSelect = { viewModel.setLanguage(it) },
        onDismiss = { langDialogOpen = false },
    )
}

@Composable
private fun AuthScreenContent(
    lang: AppLanguage,
    isSignUp: Boolean,
    displayName: String,
    email: String,
    password: String,
    errorMsg: String?,
    isSubmitting: Boolean,
    isGoogleLoading: Boolean,
    onOpenLanguageDialog: () -> Unit,
    onSelectMode: (Boolean) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onGoogleSignIn: () -> Unit,
    onSubmitEmail: () -> Unit,
    onContinueAsGuest: () -> Unit,
) {
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
                .navigationBarsPadding()
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
                    onClick = onOpenLanguageDialog,
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
                            onClick = { onSelectMode(false) },
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
                            onClick = { onSelectMode(true) },
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
                            onValueChange = onDisplayNameChange,
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
                        onValueChange = onEmailChange,
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
                        onValueChange = onPasswordChange,
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
                                text = errorMsg,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = StudioColors.RoseBright,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }

                    Button(
                        onClick = onSubmitEmail,
                        enabled = !isSubmitting && !isGoogleLoading,
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.weight(1f),
                            color = StudioColors.Border,
                        )
                        Text(
                            text = if (lang == AppLanguage.EN) "OR" else "أو",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioColors.TextSecondary,
                        )
                        HorizontalDivider(
                            modifier = Modifier.weight(1f),
                            color = StudioColors.Border,
                        )
                    }

                    OutlinedButton(
                        onClick = onGoogleSignIn,
                        enabled = !isSubmitting && !isGoogleLoading,
                        border = BorderStroke(1.dp, StudioColors.BorderBright),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = StudioColors.ElevatedSurface,
                            contentColor = StudioColors.TextPrimary,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 50.dp)
                            .testTag("auth_google_button"),
                    ) {
                        if (isGoogleLoading) {
                            CircularProgressIndicator(
                                color = StudioColors.Cyan,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.AccountCircle,
                                contentDescription = null,
                                tint = StudioColors.Cyan,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = t(lang, "auth_google_signin"),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    OutlinedButton(
                        onClick = onContinueAsGuest,
                        enabled = !isSubmitting && !isGoogleLoading,
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

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF070B14)
@Composable
private fun AuthScreenPreview() {
    VoiceProTheme {
        AuthScreenContent(
            lang = AppLanguage.AR,
            isSignUp = false,
            displayName = "",
            email = "",
            password = "",
            errorMsg = null,
            isSubmitting = false,
            isGoogleLoading = false,
            onOpenLanguageDialog = {},
            onSelectMode = {},
            onDisplayNameChange = {},
            onEmailChange = {},
            onPasswordChange = {},
            onGoogleSignIn = {},
            onSubmitEmail = {},
            onContinueAsGuest = {},
        )
    }
}
