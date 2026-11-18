package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest
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
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
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

private fun isTechnicalAuthRejection(detail: String?): Boolean {
    if (detail.isNullOrBlank()) return false
    val lower = detail.lowercase()
    return lower.contains("reauth") ||
        lower.contains("[16]") ||
        lower.contains("16:") ||
        lower.contains("blocked") ||
        lower.contains("developer") ||
        lower.contains("28444") ||
        lower.contains("signature") ||
        lower.contains("mismatch") ||
        lower.contains("cannot find") ||
        lower.contains("network") ||
        lower.contains("internal error")
}

private fun getAppSigningSha1(context: Context): String? = runCatching {
    val pm = context.packageManager
    val pkgName = context.packageName
    val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val info = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signingInfo = info.signingInfo
        if (signingInfo?.hasMultipleSigners() == true) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo?.signingCertificateHistory
        }
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES).signatures
    }
    val certBytes = signatures?.firstOrNull()?.toByteArray() ?: return@runCatching null
    val digest = MessageDigest.getInstance("SHA-1").digest(certBytes)
    digest.joinToString(":") { "%02X".format(it) }
}.getOrNull()

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
        val raw = e.localizedMessage?.takeIf { it.isNotBlank() }
            ?: e.message?.takeIf { it.isNotBlank() }
            ?: e.javaClass.simpleName
        val technicalDetail = when (e) {
            is FirebaseAuthException -> "[${e.errorCode}] $raw"
            is GetCredentialException -> {
                val msg = e.errorMessage?.toString()?.takeIf { it.isNotBlank() } ?: raw
                "[${e.type}] $msg"
            }
            else -> "${e.javaClass.simpleName}: $raw"
        }
        return if (isEn) {
            "Google Sign-In failed: $technicalDetail"
        } else {
            "تعذر تسجيل الدخول عبر Google: $technicalDetail"
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
                    val result = try {
                        val googleIdOption = GetGoogleIdOption.Builder()
                            .setFilterByAuthorizedAccounts(false)
                            .setAutoSelectEnabled(false)
                            .setServerClientId(GOOGLE_WEB_CLIENT_ID)
                            .build()
                        val request = GetCredentialRequest.Builder()
                            .addCredentialOption(googleIdOption)
                            .build()
                        kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                            cm.getCredential(activity, request)
                        } ?: run {
                            Log.w(
                                "Auth",
                                "GetGoogleIdOption timed out; retrying with GetSignInWithGoogleOption",
                            )
                            val signInWithGoogleOption =
                                GetSignInWithGoogleOption.Builder(GOOGLE_WEB_CLIENT_ID).build()
                            val fallbackRequest = GetCredentialRequest.Builder()
                                .addCredentialOption(signInWithGoogleOption)
                                .build()
                            kotlinx.coroutines.withTimeoutOrNull(25_000L) {
                                cm.getCredential(activity, fallbackRequest)
                            }
                        }
                    } catch (firstErr: GetCredentialException) {
                        val firstDetail =
                            firstErr.errorMessage?.toString() ?: firstErr.localizedMessage ?: firstErr.message
                        val shouldRetryWithFullGoogleFlow =
                            firstErr is NoCredentialException || isTechnicalAuthRejection(firstDetail)
                        if (!shouldRetryWithFullGoogleFlow) {
                            throw firstErr
                        }
                        Log.w(
                            "Auth",
                            "GetGoogleIdOption failed ($firstDetail); retrying with GetSignInWithGoogleOption",
                            firstErr,
                        )
                        val signInWithGoogleOption =
                            GetSignInWithGoogleOption.Builder(GOOGLE_WEB_CLIENT_ID).build()
                        val fallbackRequest = GetCredentialRequest.Builder()
                            .addCredentialOption(signInWithGoogleOption)
                            .build()
                        kotlinx.coroutines.withTimeoutOrNull(25_000L) {
                            cm.getCredential(activity, fallbackRequest)
                        }
                    }

                    if (result == null) {
                        val sha1 = getAppSigningSha1(activity)
                        val causeText = buildString {
                            append("Google Credential Manager timed out without a response from Google Play Services.")
                            if (!sha1.isNullOrBlank()) {
                                append(" | App SHA-1: ")
                                append(sha1)
                            }
                        }
                        AppDiagnosticLog.logRecoverable(
                            toolName = "GoogleSignIn",
                            titleAr = "انتهت مهلة استجابة تسجيل الدخول بجوجل",
                            titleEn = "Google Sign-In Timed Out",
                            causeAr = causeText,
                            causeEn = causeText,
                            errorCode = "CREDENTIAL_MANAGER_TIMEOUT",
                        )
                        errorMsg = if (isEn) {
                            "Google Sign-In did not receive a response from Google Play Services. Check your connection, ensure Google Play Services is updated, or try Email / Guest login."
                        } else {
                            "لم تستجب خدمات Google Play لطلب تسجيل الدخول خلال المهلة المحددة. تأكد من الاتصال بالإنترنت وتحديث خدمات Google Play، أو استخدم البريد الإلكتروني أو الدخول كضيف."
                        }
                        return@launch
                    }

                    val credential = result.credential
                    if (credential is CustomCredential &&
                        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                    ) {
                        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                        val idToken = googleIdTokenCredential.idToken
                        val authResult = kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                            auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                                .await()
                        }
                        if (authResult == null) {
                            AppDiagnosticLog.logRecoverable(
                                toolName = "GoogleSignIn",
                                titleAr = "انتهت مهلة المصادقة مع Firebase",
                                titleEn = "Firebase Auth Timed Out",
                                causeAr = "FirebaseAuth.signInWithCredential timed out after 20s",
                                causeEn = "FirebaseAuth.signInWithCredential timed out after 20s",
                                errorCode = "FIREBASE_AUTH_TIMEOUT",
                            )
                            errorMsg = if (isEn) {
                                "Firebase authentication timed out. Please check your internet connection and try again."
                            } else {
                                "انتهت مهلة التحقق مع خادم Firebase. تأكد من جودة الإنترنت وحاول مرة أخرى."
                            }
                            return@launch
                        }
                        finishFirebaseGoogleUser(
                            authResult.user ?: auth.currentUser,
                        )
                    } else {
                        val credType = credential.type
                        AppDiagnosticLog.logRecoverable(
                            toolName = "GoogleSignIn",
                            titleAr = "نوع بيانات اعتماد غير متوقع من جوجل",
                            titleEn = "Unexpected Google Credential Type",
                            causeAr = "Unexpected credential type: $credType",
                            causeEn = "Unexpected credential type: $credType",
                            errorCode = "UnexpectedCredentialType",
                        )
                        errorMsg = if (isEn) {
                            "Unexpected credential type returned by Google Sign-In ($credType)."
                        } else {
                            "تم استلام نوع بيانات اعتماد غير متوقع من Google ($credType)."
                        }
                    }
                } catch (e: GetCredentialCancellationException) {
                    val cancelDetail = (e.errorMessage?.toString() ?: e.localizedMessage ?: e.message)
                        ?.takeIf { it.isNotBlank() }
                    val technicalRejection = isTechnicalAuthRejection(cancelDetail)
                    val sha1 = if (technicalRejection) getAppSigningSha1(activity) else null
                    val causeText = buildString {
                        append(cancelDetail ?: e.javaClass.simpleName)
                        if (!sha1.isNullOrBlank()) {
                            append(" | App SHA-1: ")
                            append(sha1)
                        }
                    }
                    Log.w("Auth", "Google Sign-In stopped: $causeText", e)
                    AppDiagnosticLog.logRecoverable(
                        toolName = "GoogleSignIn",
                        titleAr = if (technicalRejection) {
                            "رفض التحقق من حساب جوجل في Google Play"
                        } else {
                            "إلغاء نافذة تسجيل الدخول بجوجل"
                        },
                        titleEn = if (technicalRejection) {
                            "Google Play Account Reauth Failed"
                        } else {
                            "Google Sign-In Cancelled"
                        },
                        causeAr = causeText,
                        causeEn = causeText,
                        errorCode = e.type,
                        throwable = e,
                    )
                    errorMsg = if (technicalRejection) {
                        if (isEn) {
                            "Google Play failed account verification ($cancelDetail)." +
                                (sha1?.let { "\nAdd this app SHA-1 in Firebase Console (${activity.packageName}):\n$it" } ?: "") +
                                "\nAlso ensure device screen lock is enabled and Google Play Services is updated."
                        } else {
                            "فشل التحقق من الحساب في Google Play ($cancelDetail)." +
                                (sha1?.let { "\nتأكد من إضافة بصمة SHA-1 التالية في Firebase Console لحزمة ${activity.packageName}:\n$it" } ?: "") +
                                "\nوتأكد أيضاً من تفعيل قفل شاشة الجهاز وتحديث خدمات Google Play."
                        }
                    } else if (isEn) {
                        if (cancelDetail != null) {
                            "Sign-in was cancelled ($cancelDetail). Please select a Google account to continue."
                        } else {
                            "Sign-in was cancelled. Please select a Google account to continue."
                        }
                    } else {
                        if (cancelDetail != null) {
                            "تم إلغاء تسجيل الدخول ($cancelDetail). يرجى اختيار حساب Google للمتابعة."
                        } else {
                            "تم إلغاء تسجيل الدخول. يرجى اختيار حساب Google للمتابعة."
                        }
                    }
                } catch (e: NoCredentialException) {
                    val detail = e.errorMessage?.toString() ?: e.localizedMessage ?: e.message ?: e.javaClass.simpleName
                    Log.w("Auth", "No Google credentials found on device: $detail", e)
                    AppDiagnosticLog.logRecoverable(
                        toolName = "GoogleSignIn",
                        titleAr = "لا توجد بيانات اعتماد متاحة من جوجل",
                        titleEn = "No Google Credentials Found",
                        causeAr = detail,
                        causeEn = detail,
                        errorCode = e.type,
                        throwable = e,
                    )
                    errorMsg = if (isEn) {
                        "No Google credential available ($detail). Add a Google account in Settings or use Email / Guest mode."
                    } else {
                        "لا يوجد حساب Google متاح على هذا الجهاز ($detail). أضف حساباً من إعدادات الجهاز أو ادخل بالبريد الإلكتروني أو كضيف."
                    }
                } catch (e: GetCredentialException) {
                    val detail = e.errorMessage?.toString() ?: e.localizedMessage ?: e.message ?: e.javaClass.simpleName
                    Log.e("Auth", "Google Credential Manager error: $detail", e)
                    AppDiagnosticLog.logRecoverable(
                        toolName = "GoogleSignIn",
                        titleAr = "تعذر إتمام تسجيل الدخول بحساب جوجل",
                        titleEn = "Google Sign-In Failed",
                        causeAr = detail,
                        causeEn = detail,
                        errorCode = e.type,
                        throwable = e,
                    )
                    errorMsg = formatGoogleAuthFailure(e)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val detail = e.localizedMessage ?: e.message ?: e.javaClass.simpleName
                    Log.e("Auth", "Google Sign-In failed: $detail", e)
                    AppDiagnosticLog.logRecoverable(
                        toolName = "GoogleSignIn",
                        titleAr = "تعذر إتمام تسجيل الدخول بحساب جوجل",
                        titleEn = "Google Sign-In Failed",
                        causeAr = detail,
                        causeEn = detail,
                        errorCode = (e as? FirebaseAuthException)?.errorCode ?: e.javaClass.simpleName,
                        throwable = e,
                    )
                    errorMsg = formatGoogleAuthFailure(e)
                } finally {
                    isGoogleLoading = false
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
