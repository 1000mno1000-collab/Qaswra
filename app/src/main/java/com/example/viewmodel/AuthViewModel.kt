package com.example.viewmodel

import android.app.Application
import android.util.Log
import android.util.Patterns
import androidx.lifecycle.AndroidViewModel
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.google.firebase.auth.userProfileChangeRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "AuthViewModel"

sealed interface AuthSessionState {
    data class SignedIn(
        val uid: String,
        val displayName: String,
        val email: String
    ) : AuthSessionState

    data object Guest : AuthSessionState
    data object SignedOut : AuthSessionState
}

data class AuthUiState(
    val session: AuthSessionState = AuthSessionState.SignedOut,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val auth: FirebaseAuth? = runCatching {
        if (FirebaseApp.getApps(application).isEmpty()) {
            FirebaseApp.initializeApp(application)
        }
        Firebase.auth
    }.getOrNull()

    private val _uiState = MutableStateFlow(
        AuthUiState(
            session = auth?.currentUser?.toSignedInState() ?: AuthSessionState.SignedOut
        )
    )
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        _uiState.update { state ->
            when {
                user != null -> state.copy(
                    session = user.toSignedInState(),
                    isLoading = false,
                    errorMessage = null
                )
                state.session is AuthSessionState.Guest -> state
                else -> state.copy(
                    session = AuthSessionState.SignedOut,
                    isLoading = false
                )
            }
        }
    }

    init {
        auth?.addAuthStateListener(authStateListener)
    }

    fun continueAsGuest() {
        _uiState.update {
            it.copy(
                session = AuthSessionState.Guest,
                isLoading = false,
                errorMessage = null,
                infoMessage = null
            )
        }
    }

    fun requestSignIn() {
        _uiState.update {
            it.copy(
                session = AuthSessionState.SignedOut,
                isLoading = false,
                errorMessage = null,
                infoMessage = null
            )
        }
    }

    fun startGoogleSignInLoading() {
        _uiState.update {
            it.copy(isLoading = true, errorMessage = null, infoMessage = null)
        }
    }

    fun onGoogleSignInCancelled() {
        Log.w(TAG, "Google Credential Manager flow was cancelled")
        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = null
            )
        }
    }

    fun onGoogleSignInFailure(rawMessage: String?) {
        Log.w(TAG, "Google Credential Manager error: $rawMessage")
        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = rawMessage?.takeIf { s -> s.isNotBlank() }
                    ?: "تعذر إكمال تسجيل الدخول بحساب جوجل. تأكد من توفر خدمات Google وحاول مجدداً."
            )
        }
    }

    fun signInWithGoogleIdToken(idToken: String) {
        val firebaseAuth = auth ?: run {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = "خدمة المصادقة غير مهيأة حالياً."
                )
            }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        firebaseAuth.signInWithCredential(credential)
            .addOnSuccessListener { result ->
                val user = result.user
                _uiState.update {
                    it.copy(
                        session = user?.toSignedInState() ?: AuthSessionState.SignedOut,
                        isLoading = false,
                        errorMessage = null
                    )
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Firebase Google credential sign-in failed", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = mapAuthExceptionToArabic(e)
                    )
                }
            }
    }

    fun signInWithEmail(email: String, password: String) {
        val cleanEmail = email.trim()
        if (!validateEmailAndPassword(cleanEmail, password, isSignUp = false)) return
        val firebaseAuth = auth ?: return
        _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
        firebaseAuth.signInWithEmailAndPassword(cleanEmail, password)
            .addOnSuccessListener { result ->
                _uiState.update {
                    it.copy(
                        session = result.user?.toSignedInState() ?: AuthSessionState.SignedOut,
                        isLoading = false,
                        errorMessage = null
                    )
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Email sign-in failed", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = mapAuthExceptionToArabic(e)
                    )
                }
            }
    }

    fun createAccountWithEmail(displayName: String, email: String, password: String) {
        val cleanEmail = email.trim()
        val cleanName = displayName.trim()
        if (!validateEmailAndPassword(cleanEmail, password, isSignUp = true)) return
        val firebaseAuth = auth ?: return
        _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
        firebaseAuth.createUserWithEmailAndPassword(cleanEmail, password)
            .addOnSuccessListener { result ->
                val user = result.user
                if (user != null && cleanName.isNotBlank()) {
                    user.updateProfile(
                        userProfileChangeRequest { this.displayName = cleanName }
                    ).addOnCompleteListener {
                        _uiState.update {
                            it.copy(
                                session = user.toSignedInState(fallbackName = cleanName),
                                isLoading = false,
                                errorMessage = null
                            )
                        }
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            session = user?.toSignedInState() ?: AuthSessionState.SignedOut,
                            isLoading = false,
                            errorMessage = null
                        )
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Email sign-up failed", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = mapAuthExceptionToArabic(e)
                    )
                }
            }
    }

    fun sendPasswordReset(email: String) {
        val cleanEmail = email.trim()
        if (cleanEmail.isBlank() || !Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
            _uiState.update {
                it.copy(errorMessage = "يرجى إدخال بريد إلكتروني صحيح لإرسال رابط استعادة كلمة المرور.")
            }
            return
        }
        val firebaseAuth = auth ?: return
        _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
        firebaseAuth.sendPasswordResetEmail(cleanEmail)
            .addOnSuccessListener {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        infoMessage = "تم إرسال رابط إعادة تعيين كلمة المرور إلى $cleanEmail"
                    )
                }
            }
            .addOnFailureListener { e ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = mapAuthExceptionToArabic(e)
                    )
                }
            }
    }

    fun signOut() {
        runCatching { auth?.signOut() }
        _uiState.update {
            it.copy(
                session = AuthSessionState.SignedOut,
                isLoading = false,
                errorMessage = null,
                infoMessage = null
            )
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, infoMessage = null) }
    }

    private fun validateEmailAndPassword(
        email: String,
        password: String,
        isSignUp: Boolean
    ): Boolean {
        if (email.isBlank() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            _uiState.update { it.copy(errorMessage = "يرجى إدخال بريد إلكتروني صحيح.") }
            return false
        }
        if (password.length < 6) {
            _uiState.update {
                it.copy(
                    errorMessage = if (isSignUp) {
                        "كلمة المرور يجب أن تكون 6 أحرف على الأقل."
                    } else {
                        "يرجى إدخال كلمة المرور (6 أحرف فأكثر)."
                    }
                )
            }
            return false
        }
        return true
    }

    private fun mapAuthExceptionToArabic(e: Throwable): String {
        val code = (e as? FirebaseAuthException)?.errorCode.orEmpty()
        val msg = e.localizedMessage.orEmpty().lowercase()
        return when {
            code == "ERROR_INVALID_EMAIL" -> "صيغة البريد الإلكتروني غير صحيحة."
            code == "ERROR_WRONG_PASSWORD" || code == "ERROR_INVALID_CREDENTIAL" ->
                "البريد الإلكتروني أو كلمة المرور غير صحيحة."
            code == "ERROR_USER_NOT_FOUND" -> "لا يوجد حساب مسجل بهذا البريد الإلكتروني."
            code == "ERROR_EMAIL_ALREADY_IN_USE" -> "هذا البريد الإلكتروني مستخدم بالفعل بحساب آخر."
            code == "ERROR_WEAK_PASSWORD" -> "كلمة المرور ضعيفة، يرجى اختيار كلمة أقوى."
            code == "ERROR_OPERATION_NOT_ALLOWED" || "operation-not-allowed" in msg ->
                "تسجيل الدخول بالبريد غير مفعّل بعد في مشروع Firebase، يمكنك استخدام حساب جوجل أو المتابعة كضيف."
            "network" in msg -> "تعذر الاتصال بالشبكة. تحقق من الإنترنت وحاول مجدداً."
            else -> e.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "تعذر إتمام عملية تسجيل الدخول. حاول مرة أخرى."
        }
    }

    private fun FirebaseUser.toSignedInState(fallbackName: String? = null): AuthSessionState.SignedIn {
        val resolvedName = displayName?.takeIf { it.isNotBlank() }
            ?: fallbackName?.takeIf { it.isNotBlank() }
            ?: email?.substringBefore('@')?.takeIf { it.isNotBlank() }
            ?: "مستخدم Voice Pro"
        return AuthSessionState.SignedIn(
            uid = uid,
            displayName = resolvedName,
            email = email.orEmpty()
        )
    }

    override fun onCleared() {
        super.onCleared()
        auth?.removeAuthStateListener(authStateListener)
    }
}
