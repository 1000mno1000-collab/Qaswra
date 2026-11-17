package com.example.ui.screens

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.example.R
import com.example.ui.AppLanguage
import com.example.ui.components.LanguageBadgeButton
import com.example.ui.components.LanguageSelectorDialog
import com.example.ui.theme.CoralRecord
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioMidnight
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletGlow
import com.example.viewmodel.AuthUiState
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch

private const val AUTH_TAG = "AuthScreen"

@Composable
fun AuthScreen(
    authState: AuthUiState,
    currentLanguage: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    onGoogleSignInStarted: () -> Unit,
    onGoogleIdToken: (String) -> Unit,
    onGoogleCancelled: () -> Unit,
    onGoogleError: (String?) -> Unit,
    onEmailSignIn: (String, String) -> Unit,
    onEmailSignUp: (String, String, String) -> Unit,
    onPasswordReset: (String) -> Unit,
    onContinueAsGuest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var showLanguageDialog by remember { mutableStateOf(false) }
    var isSignUpMode by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        StudioObsidian,
                        StudioMidnight,
                        VioletGlow.copy(alpha = 0.08f),
                        CyanGlow.copy(alpha = 0.06f)
                    )
                )
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 540.dp)
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                LanguageBadgeButton(
                    currentLanguage = currentLanguage,
                    onClick = { showLanguageDialog = true },
                    modifier = Modifier.testTag("auth_language_button")
                )
            }
            // Hero Studio Branding Header
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = StudioSurface,
                border = BorderStroke(1.dp, StudioBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Image(
                        painter = painterResource(id = R.drawable.img_studio_aura),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .alpha(0.30f)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = StudioSurfaceElevated,
                            border = BorderStroke(1.dp, CyanGlow.copy(alpha = 0.55f))
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = CyanGlow,
                                modifier = Modifier
                                    .padding(14.dp)
                                    .size(34.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "سجّل الدخول مجاناً أو ادخل مباشرة كضيف لبدء التسجيل والهندسة الصوتية",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            }

            if (authState.errorMessage != null) {
                Surface(
                    color = CoralRecord.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, CoralRecord.copy(alpha = 0.65f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("auth_error_banner")
                ) {
                    Text(
                        text = authState.errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            if (authState.infoMessage != null) {
                Surface(
                    color = EmeraldActive.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, EmeraldActive.copy(alpha = 0.65f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("auth_info_banner")
                ) {
                    Text(
                        text = authState.infoMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = EmeraldActive,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            // Google Sign-In Card
            Button(
                onClick = {
                    val webClientIdRes = context.resources.getIdentifier(
                        "default_web_client_id",
                        "string",
                        context.packageName
                    )
                    if (webClientIdRes == 0) {
                        onGoogleError("معرّف عميل Google غير متوفر في هذه البيئة.")
                        return@Button
                    }
                    val webClientId = context.getString(webClientIdRes)
                    if (webClientId.isBlank()) {
                        onGoogleError("معرّف عميل Google فارغ.")
                        return@Button
                    }
                    onGoogleSignInStarted()
                    coroutineScope.launch {
                        try {
                            val credentialManager = CredentialManager.create(context)
                            val googleOption = GetSignInWithGoogleOption.Builder(webClientId).build()
                            val request = GetCredentialRequest.Builder()
                                .addCredentialOption(googleOption)
                                .build()
                            val result = credentialManager.getCredential(
                                request = request,
                                context = context
                            )
                            val googleCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
                            onGoogleIdToken(googleCredential.idToken)
                        } catch (e: GetCredentialCancellationException) {
                            Log.w(AUTH_TAG, "User cancelled Google Sign-In or certificate mismatch", e)
                            onGoogleCancelled()
                        } catch (e: GetCredentialException) {
                            Log.w(AUTH_TAG, "Credential Manager sign-in error", e)
                            onGoogleError(e.localizedMessage)
                        } catch (t: Throwable) {
                            Log.e(AUTH_TAG, "Unexpected Google Sign-In failure", t)
                            onGoogleError(t.localizedMessage)
                        }
                    }
                },
                enabled = !authState.isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanGlow,
                    contentColor = StudioObsidian
                ),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("auth_google_signin_button")
            ) {
                if (authState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = StudioObsidian,
                        strokeWidth = 2.4.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("جارٍ تسجيل الدخول…", fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Rounded.AccountCircle, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.auth_google_signin),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = StudioBorder)
                Text(
                    text = "  أو بالبريد الإلكتروني  ",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = StudioBorder)
            }

            // Email & Password Card
            Surface(
                color = StudioSurface,
                border = BorderStroke(1.dp, StudioBorder),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            onClick = { isSignUpMode = false },
                            shape = RoundedCornerShape(12.dp),
                            color = if (!isSignUpMode) VioletGlow.copy(alpha = 0.22f) else StudioObsidian,
                            border = BorderStroke(1.dp, if (!isSignUpMode) VioletGlow else StudioBorder),
                            modifier = Modifier.weight(1f).testTag("auth_tab_signin")
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 11.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.auth_sign_in),
                                    color = if (!isSignUpMode) VioletGlow else TextSecondary,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        Surface(
                            onClick = { isSignUpMode = true },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSignUpMode) VioletGlow.copy(alpha = 0.22f) else StudioObsidian,
                            border = BorderStroke(1.dp, if (isSignUpMode) VioletGlow else StudioBorder),
                            modifier = Modifier.weight(1f).testTag("auth_tab_signup")
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 11.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.auth_create_account),
                                    color = if (isSignUpMode) VioletGlow else TextSecondary,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }

                    if (isSignUpMode) {
                        OutlinedTextField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            label = { Text("الاسم الكامل") },
                            leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("auth_name_input")
                        )
                    }

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("البريد الإلكتروني") },
                        leadingIcon = { Icon(Icons.Rounded.Email, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("auth_email_input")
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("كلمة المرور") },
                        leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (passwordVisible) "إخفاء كلمة المرور" else "إظهار كلمة المرور"
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("auth_password_input")
                    )

                    if (!isSignUpMode) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = { onPasswordReset(email) },
                                enabled = !authState.isLoading,
                                modifier = Modifier.testTag("auth_reset_password_button")
                            ) {
                                Text("نسيت كلمة المرور؟", color = CyanGlow, fontSize = 12.5.sp)
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (isSignUpMode) {
                                onEmailSignUp(displayName, email, password)
                            } else {
                                onEmailSignIn(email, password)
                            }
                        },
                        enabled = !authState.isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VioletGlow,
                            contentColor = StudioObsidian
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("auth_email_submit_button")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isSignUpMode) {
                                stringResource(R.string.auth_create_account)
                            } else {
                                stringResource(R.string.auth_email_signin)
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Continue as Guest (Instant Studio Access)
            OutlinedButton(
                onClick = onContinueAsGuest,
                enabled = !authState.isLoading,
                border = BorderStroke(1.dp, EmeraldActive.copy(alpha = 0.7f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = EmeraldActive),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("guest_continue_button")
            ) {
                Text(
                    text = stringResource(R.string.auth_continue_guest),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (showLanguageDialog) {
            LanguageSelectorDialog(
                currentLanguage = currentLanguage,
                onSelectLanguage = onSelectLanguage,
                onDismiss = { showLanguageDialog = false }
            )
        }
    }
}
