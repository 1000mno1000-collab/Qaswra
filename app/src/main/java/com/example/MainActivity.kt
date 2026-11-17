package com.example

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ai.configureAppCheck
import com.example.ui.LocaleHelper
import com.example.ui.VoiceProApp
import com.example.ui.theme.VoiceProTheme
import com.example.viewmodel.AuthViewModel
import com.example.viewmodel.StudioViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: StudioViewModel by viewModels()
    private val authViewModel: AuthViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        val saved = LocaleHelper.getSavedLanguage(newBase)
        super.attachBaseContext(LocaleHelper.createLocalizedContext(newBase, saved))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure App Check with launch intent before rendering UI, creating ViewModels, or Firebase operations
        configureAppCheck(this, intent)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            VoiceProTheme {
                VoiceProApp(
                    viewModel = viewModel,
                    authViewModel = authViewModel
                )
            }
        }
    }
}
