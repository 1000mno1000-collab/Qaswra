package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.example.SonixCrashGuard
import com.example.diagnostics.AppDiagnosticLog
import com.example.i18n.t
import com.example.model.AppLanguage
import com.example.studio.ui.lit
import com.example.ui.components.LanguageBadgeButton
import com.example.ui.components.LanguageSelectorDialog
import com.example.ui.theme.SonixTheme
import com.example.ui.theme.StudioColors
import com.example.viewmodel.StudioUiState
import com.example.viewmodel.StudioViewModel
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val GOOGLE_WEB_CLIENT_ID =
    "71794555883-1j2b8ckt6mriegaem2vji34ov5vofpb4.apps.googleusercontent.com"

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun AuthScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onLoginSuccess: () -> Unit = {},
) {
    val lang = uiState.language
    val isEn = lang == AppLanguage.EN
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) {
        runCatching { CredentialManager.create(context) }.getOrNull()
    }

    var isSignUp by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var isGoogleLoading by remember { mutableStateOf(false) }
    var langDialogOpen by remember { mutableStateOf(false) }

    fun finishFirebaseGoogleUser(user: FirebaseUser?) {
        isGoogleLoading = false
        if (user != null) {
            val resolvedName = user.displayName?.takeIf { it.isNotBlank() }
                ?: user.email?.substringBefore('@').orEmpty()
            val resolvedEmail = user.email.orEmpty()
            viewModel.onGoogleLoginSuccess(
                uid = user.uid,
                displayName = resolvedName,
                email = resolvedEmail,
            )
            onLoginSuccess()
        } else {
            errorMsg = if (isEn) {
                "Sign-in completed but no Firebase user session was returned."
            } else {
                "تم التحقق من الحساب ولكن تعذر استرجاع بيانات الجلسة."
            }
        }
    }

    fun formatGoogleAuthFailure(e: Exception): String {
        val raw = e.localizedMessage ?: e.javaClass.simpleName
        val isCanceled = raw.contains("canceled", ignoreCase = true) ||
            raw.contains("cancelled", ignoreCase = true)
        return if (isCanceled) {
            if (isEn) {
                "Google Sign-In was canceled before completion. Please try again."
            } else {
                "تم إلغاء تسجيل الدخول بحساب Google قبل إتمامه. يرجى المحاولة مرة أخرى."
            }
        } else {
            if (isEn) {
                "Google Sign-In failed: $raw"
            } else {
                "تعذر تسجيل الدخول عبر Google: $raw"
            }
        }
    }

    fun submitEmailAuth() {
        if (isSubmitting || isGoogleLoading) return
        val cleanEmail = email.trim()
        if (!Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(cleanEmail)) {
            errorMsg = if (isEn) {
                "Please enter a valid email address."
            } else {
                "يرجى إدخال بريد إلكتروني صحيح."
            }
            return
        }
        if (password.length < 6) {
            errorMsg = if (isEn) {
                "Please enter a password of at least 6 characters."
            } else {
                "يرجى إدخال كلمة مرور صالحة (6 أحرف على الأقل)."
            }
            return
        }
        isSubmitting = true
        errorMsg = null
        scope.launch {
            try {
                val err = if (isSignUp) {
                    viewModel.createAccountWithEmail(displayName, cleanEmail, password)
                } else {
                    viewModel.signInWithEmail(cleanEmail, password)
                }
                if (err == null) {
                    onLoginSuccess()
                } else {
                    errorMsg = err
                }
            } finally {
                isSubmitting = false
            }
        }
    }

    // Restore existing Firebase session on startup
    LaunchedEffect(isPreview) {
        if (isPreview) return@LaunchedEffect
        runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) {
                SonixCrashGuard.safeInitializeFirebase(context)
            }
        }
        val auth = runCatching { FirebaseAuth.getInstance() }.getOrNull() ?: return@LaunchedEffect
        val existingUser = runCatching { auth.currentUser }.getOrNull()
        if (existingUser != null) {
            finishFirebaseGoogleUser(existingUser)
        }
    }

    AuthScreenContent(
        lang = lang,
        isSignUp = isSignUp,
        displayName = displayName,
        email = email,
        password = password,
        passwordVisible = passwordVisible,
        errorMsg = errorMsg,
        isSubmitting = isSubmitting,
        isGoogleLoading = isGoogleLoading,
        onOpenLanguageDialog = { langDialogOpen = true },
        onSelectMode = { signUp ->
            isSignUp = signUp
            errorMsg = null
        },
        onDisplayNameChange = {
            displayName = it
            errorMsg = null
        },
        onEmailChange = {
            email = it
            errorMsg = null
        },
        onPasswordChange = {
            password = it
            errorMsg = null
        },
        onTogglePasswordVisibility = { passwordVisible = !passwordVisible },
        onGoogleSignIn = {
            if (isSubmitting || isGoogleLoading) return@AuthScreenContent
            runCatching {
                if (FirebaseApp.getApps(context).isEmpty()) {
                    SonixCrashGuard.safeInitializeFirebase(context)
                }
            }.onFailure { e ->
                Log.e("Auth", "FirebaseApp manual initialization failed", e)
            }

            val activity = context.findActivity()
            if (activity == null) {
                errorMsg = if (isEn) {
                    "Unable to find Activity to launch Google Sign-In."
                } else {
                    "تعذر العثور على نافذة التطبيق لفتح تسجيل الدخول بحساب Google."
                }
                return@AuthScreenContent
            }

            val cm = credentialManager ?: runCatching { CredentialManager.create(activity) }.getOrNull()
            if (cm == null) {
                errorMsg = if (isEn) {
                    "Credential Manager is unavailable on this device."
                } else {
                    "مدير بيانات الاعتماد غير متاح على هذا الجهاز حالياً."
                }
                return@AuthScreenContent
            }

            val auth = runCatching { FirebaseAuth.getInstance() }.getOrElse { e ->
                Log.e("Auth", "FirebaseAuth unavailable", e)
                errorMsg = if (isEn) {
                    "Firebase Auth is not initialized: ${e.localizedMessage ?: e.javaClass.simpleName}"
                } else {
                    "تعذر تهيئة مصادقة Firebase: ${e.localizedMessage ?: e.javaClass.simpleName}"
                }
                return@AuthScreenContent
            }

            isGoogleLoading = true
            errorMsg = null
            scope.launch {
                try {
                    val googleIdOption = GetGoogleIdOption.Builder()
                        .setFilterByAuthorizedAccounts(false)
                        .setServerClientId(GOOGLE_WEB_CLIENT_ID)
                        .build()
                    val request = GetCredentialRequest.Builder()
                        .addCredentialOption(googleIdOption)
                        .build()
                    val result = cm.getCredential(activity, request)
                    val credential = result.credential
                    if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                        val authResult = auth.signInWithCredential(
                            GoogleAuthProvider.getCredential(idToken, null),
                        ).await()
                        finishFirebaseGoogleUser(authResult.user ?: auth.currentUser)
                    } else {
                        isGoogleLoading = false
                        errorMsg = if (isEn) {
                            "Unexpected credential type returned by Google Sign-In."
                        } else {
                            "تم استلام نوع بيانات اعتماد غير متوقع من Google."
                        }
                    }
                } catch (e: GetCredentialCancellationException) {
                    Log.w("Auth", "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
                    isGoogleLoading = false
                    errorMsg = if (isEn) {
                        "Sign-in was cancelled. Please select a Google account to continue."
                    } else {
                        "تم إلغاء تسجيل الدخول. يرجى اختيار حساب Google للمتابعة."
                    }
                } catch (e: NoCredentialException) {
                    Log.w("Auth", "No Google credentials found on device: ${e.message}", e)
                    isGoogleLoading = false
                    errorMsg = if (isEn) {
                        "No Google account is signed in on this device. Add one in Settings, or use Email / Guest mode."
                    } else {
                        "لا يوجد حساب Google مسجل على هذا الجهاز. أضف حساباً من إعدادات الجهاز أو ادخل بالبريد الإلكتروني أو كضيف."
                    }
                } catch (e: GetCredentialException) {
                    Log.e("Auth", "Google Credential Manager error", e)
                    isGoogleLoading = false
                    AppDiagnosticLog.logRecoverable(
                        toolName = "GoogleSignIn",
                        titleAr = "تعذر إتمام تسجيل الدخول بحساب جوجل",
                        titleEn = "Google Sign-In Failed",
                        causeAr = e.localizedMessage ?: e.javaClass.simpleName,
                        causeEn = e.localizedMessage ?: e.javaClass.simpleName,
                        errorCode = e.javaClass.simpleName,
                        throwable = e,
                    )
                    errorMsg = formatGoogleAuthFailure(e)
                } catch (e: CancellationException) {
                    isGoogleLoading = false
                    throw e
                } catch (e: Exception) {
                    Log.e("Auth", "Google Sign-In failed", e)
                    isGoogleLoading = false
                    AppDiagnosticLog.logRecoverable(
                        toolName = "GoogleSignIn",
                        titleAr = "تعذر إتمام تسجيل الدخول بحساب جوجل",
                        titleEn = "Google Sign-In Failed",
                        causeAr = e.localizedMessage ?: e.javaClass.simpleName,
                        causeEn = e.localizedMessage ?: e.javaClass.simpleName,
                        errorCode = e.javaClass.simpleName,
                        throwable = e,
                    )
                    errorMsg = formatGoogleAuthFailure(e)
                }
            }
        },
        onSubmitEmail = { submitEmailAuth() },
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
    passwordVisible: Boolean,
    errorMsg: String?,
    isSubmitting: Boolean,
    isGoogleLoading: Boolean,
    onOpenLanguageDialog: () -> Unit,
    onSelectMode: (Boolean) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onGoogleSignIn: () -> Unit,
    onSubmitEmail: () -> Unit,
    onContinueAsGuest: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val isEn = lang == AppLanguage.EN

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LanguageBadgeButton(
                currentLanguage = lang,
                onClick = onOpenLanguageDialog,
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val logoBorderBrush = remember {
                    Brush.linearGradient(
                        colors = listOf(StudioColors.Cyan, StudioColors.Violet),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = Color(0xFFFFFFFE),
                    border = BorderStroke(
                        width = 1.8.dp,
                        brush = logoBorderBrush,
                    ),
                    shadowElevation = 14.dp,
                    modifier = Modifier.size(88.dp),
                ) {
                    Image(
                        bitmap = ImageBitmap.imageResource(R.drawable.sonix_primary_logo),
                        contentDescription = t(lang, "app_name"),
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.High,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(24.dp)),
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = t(lang, "app_name"),
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            textDirection = TextDirection.Ltr,
                        ),
                        color = StudioColors.TextPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
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
                            val signInInteraction = remember { MutableInteractionSource() }
                            val signUpInteraction = remember { MutableInteractionSource() }
                            Surface(
                                onClick = { onSelectMode(false) },
                                interactionSource = signInInteraction,
                                shape = RoundedCornerShape(14.dp),
                                color = signInColor,
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .lit(signInInteraction, MaterialTheme.colorScheme.primary, selected = !isSignUp, cornerRadius = 14.dp)
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
                                interactionSource = signUpInteraction,
                                shape = RoundedCornerShape(14.dp),
                                color = signUpColor,
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .lit(signUpInteraction, MaterialTheme.colorScheme.primary, selected = isSignUp, cornerRadius = 14.dp)
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
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(
                                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                                ),
                                leadingIcon = {
                                    Icon(Icons.Rounded.Person, contentDescription = null, tint = StudioColors.Cyan)
                                },
                                label = {
                                    Text(if (isEn) "Display Name" else "الاسم في الاستوديو")
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
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next,
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) },
                            ),
                            leadingIcon = {
                                Icon(Icons.Rounded.Email, contentDescription = null, tint = StudioColors.Cyan)
                            },
                            label = {
                                Text(if (isEn) "Email" else "البريد الإلكتروني")
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
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                    onSubmitEmail()
                                },
                            ),
                            leadingIcon = {
                                Icon(Icons.Rounded.Lock, contentDescription = null, tint = StudioColors.Cyan)
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = onTogglePasswordVisibility,
                                    modifier = Modifier.testTag("auth_password_visibility"),
                                ) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = if (passwordVisible) {
                                            if (isEn) "Hide password" else "إخفاء كلمة المرور"
                                        } else {
                                            if (isEn) "Show password" else "إظهار كلمة المرور"
                                        },
                                        tint = StudioColors.TextSecondary,
                                    )
                                }
                            },
                            label = {
                                Text(if (isEn) "Password (6+ chars)" else "كلمة المرور (6 أحرف فأكثر)")
                            },
                            colors = fieldColors,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_password_input"),
                        )

                        val submitInteraction = remember { MutableInteractionSource() }
                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                onSubmitEmail()
                            },
                            enabled = !isSubmitting && !isGoogleLoading,
                            interactionSource = submitInteraction,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StudioColors.Cyan,
                                contentColor = StudioColors.BgTop,
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 50.dp)
                                .lit(submitInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
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
                                text = if (isEn) "OR" else "أو",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioColors.TextSecondary,
                            )
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = StudioColors.Border,
                            )
                        }

                        val googleInteraction = remember { MutableInteractionSource() }
                        OutlinedButton(
                            onClick = {
                                focusManager.clearFocus()
                                onGoogleSignIn()
                            },
                            enabled = !isSubmitting && !isGoogleLoading,
                            interactionSource = googleInteraction,
                            border = BorderStroke(1.dp, StudioColors.BorderBright),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = StudioColors.ElevatedSurface,
                                contentColor = StudioColors.TextPrimary,
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 50.dp)
                                .lit(googleInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
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

                        if (!errorMsg.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = StudioColors.Rose.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, StudioColors.Rose.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("auth_error_banner"),
                            ) {
                                Text(
                                    text = errorMsg,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = StudioColors.RoseBright,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }

                        val guestInteraction = remember { MutableInteractionSource() }
                        OutlinedButton(
                            onClick = onContinueAsGuest,
                            enabled = !isSubmitting && !isGoogleLoading,
                            interactionSource = guestInteraction,
                            border = BorderStroke(1.dp, StudioColors.Emerald.copy(alpha = 0.7f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.Emerald),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 50.dp)
                                .lit(guestInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
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

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF070B14)
@Composable
private fun AuthScreenPreview() {
    SonixTheme {
        AuthScreenContent(
            lang = AppLanguage.AR,
            isSignUp = false,
            displayName = "",
            email = "",
            password = "",
            passwordVisible = false,
            errorMsg = null,
            isSubmitting = false,
            isGoogleLoading = false,
            onOpenLanguageDialog = {},
            onSelectMode = {},
            onDisplayNameChange = {},
            onEmailChange = {},
            onPasswordChange = {},
            onTogglePasswordVisibility = {},
            onGoogleSignIn = {},
            onSubmitEmail = {},
            onContinueAsGuest = {},
        )
    }
}
