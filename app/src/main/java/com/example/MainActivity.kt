package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.model.AuthSession
import com.example.model.StudioRoute
import com.example.ui.components.PersistentTransportBar
import com.example.ui.screens.AiStudioScreen
import com.example.ui.screens.AiToolDetailScreen
import com.example.ui.screens.AudioEditorScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.EchoDetailScreen
import com.example.ui.screens.EchoReverbScreen
import com.example.ui.screens.EffectDetailScreen
import com.example.ui.screens.EffectsChainScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.PresetsScreen
import com.example.ui.screens.ProjectsExportScreen
import com.example.ui.screens.RecordingStudioScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TextToSpeechStudioScreen
import com.example.ui.screens.VoicePurifierScreen
import com.example.ui.screens.VoiceTransformerScreen
import com.example.ui.theme.StudioColors
import com.example.ui.theme.VoiceProTheme
import com.example.viewmodel.StudioSideEffect
import com.example.viewmodel.StudioViewModel

private class CreateTypedDocumentContract : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent {
        val (mimeType, suggestedName) = input
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, suggestedName)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return if (resultCode == Activity.RESULT_OK) intent?.data else null
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { splashView ->
                splashView.animate()
                    .alpha(0f)
                    .setDuration(240L)
                    .withEndAction { splashView.remove() }
                    .start()
            }
        }
        enableEdgeToEdge()
        setContent {
            VoiceProTheme {
                VoiceProRootApp()
            }
        }
        window.decorView.post {
            window.setBackgroundDrawable(ColorDrawable(0xFF070B14.toInt()))
        }
    }
}

@Composable
private fun VoiceProRootApp(
    viewModel: StudioViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val layoutDirection = if (uiState.language.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = CreateTypedDocumentContract(),
    ) { uri ->
        viewModel.completeSaveExportedDocument(uri)
    }

    LaunchedEffect(viewModel) {
        viewModel.sideEffects.collect { effect ->
            when (effect) {
                is StudioSideEffect.PromptSaveDocument -> {
                    saveDocumentLauncher.launch(effect.result.mimeType to effect.suggestedName)
                }
                is StudioSideEffect.LaunchShareIntent -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = effect.mimeType
                        putExtra(Intent.EXTRA_STREAM, effect.uri)
                        putExtra(Intent.EXTRA_SUBJECT, effect.title)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(shareIntent, effect.title)
                    context.startActivity(chooser)
                }
            }
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            StudioColors.BgTop,
                            StudioColors.BgMid,
                            StudioColors.BgTop,
                        ),
                    ),
                ),
        ) {
            if (uiState.authSession is AuthSession.SignedOut) {
                AuthScreen(uiState = uiState, viewModel = viewModel)
            } else {
                val navController = rememberNavController()

                Scaffold(
                    containerColor = Color.Transparent,
                    bottomBar = {
                        PersistentTransportBar(
                            uiState = uiState,
                            onPlayPause = { viewModel.togglePlayPause() },
                            onStop = { viewModel.stopPlayback() },
                            onSeekFraction = { viewModel.seekToFraction(it) },
                            onToggleAutoLoop = { viewModel.setAutoLoopOnPlayback(!uiState.autoLoopOnPlayback) },
                            onToggleBypass = { viewModel.toggleBypass() },
                            onHoldBypassChange = { viewModel.setHoldBypass(it) },
                        )
                    },
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(bottom = innerPadding.calculateBottomPadding()),
                    ) {
                        uiState.statusBanner?.let { banner ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = StudioColors.ElevatedSurface,
                                    border = BorderStroke(1.dp, StudioColors.Cyan.copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .widthIn(max = 640.dp),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            text = banner,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = StudioColors.Cyan,
                                            modifier = Modifier.weight(1f),
                                        )
                                        IconButton(
                                            onClick = { viewModel.clearBanner() },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .testTag("dismiss_banner_button"),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = "Dismiss",
                                                tint = StudioColors.TextSecondary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        NavHost(
                            navController = navController,
                            startDestination = StudioRoute.Home,
                            modifier = Modifier.weight(1f),
                        ) {
                            composable<StudioRoute.Home> {
                                HomeScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onNavigate = { route ->
                                        navController.navigate(route) {
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                )
                            }
                            composable<StudioRoute.Record> {
                                RecordingStudioScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.VoicePurifier> {
                                VoicePurifierScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.EchoSpace> {
                                EchoReverbScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onOpenDetail = { grp -> navController.navigate(StudioRoute.EchoDetail(grp)) },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.EchoDetail> { entry ->
                                val dest = entry.toRoute<StudioRoute.EchoDetail>()
                                EchoDetailScreen(
                                    group = dest.group,
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.EffectsChain> {
                                EffectsChainScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onOpenEffect = { eff -> navController.navigate(StudioRoute.EffectDetail(eff)) },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.EffectDetail> { entry ->
                                val dest = entry.toRoute<StudioRoute.EffectDetail>()
                                EffectDetailScreen(
                                    effect = dest.effect,
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onOpenEchoDetail = { grp -> navController.navigate(StudioRoute.EchoDetail(grp)) },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.Editor> {
                                AudioEditorScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.Presets> {
                                PresetsScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.ProjectsExport> {
                                ProjectsExportScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.AiStudio> {
                                AiStudioScreen(
                                    uiState = uiState,
                                    onOpenTool = { tool -> navController.navigate(StudioRoute.AiToolDetail(tool)) },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.AiToolDetail> { entry ->
                                val dest = entry.toRoute<StudioRoute.AiToolDetail>()
                                AiToolDetailScreen(
                                    tool = dest.tool,
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.TtsStudio> {
                                TextToSpeechStudioScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.VoiceTransformer> {
                                VoiceTransformerScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.Settings> {
                                SettingsScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
