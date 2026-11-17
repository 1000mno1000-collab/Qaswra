package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.i18n.t
import com.example.model.AudioPickerTarget
import com.example.model.AuthSession
import com.example.model.StudioRoute
import com.example.ui.components.PersistentTransportBar
import com.example.ui.components.SleepTimerDialog
import com.example.ui.screens.AiStudioScreen
import com.example.ui.screens.AiToolDetailScreen
import com.example.ui.screens.AudioEditorScreen
import com.example.ui.screens.AudioMergeScreen
import com.example.ui.screens.AudioSplitScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CompressAudioScreen
import com.example.ui.screens.EchoDetailScreen
import com.example.ui.screens.EchoReverbScreen
import com.example.ui.screens.EffectDetailScreen
import com.example.ui.screens.EffectsChainScreen
import com.example.ui.screens.ExtractVideoAudioScreen
import com.example.ui.screens.FormatConverterScreen
import com.example.ui.screens.GenerateSilenceScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.InAppAudioPickerScreen
import com.example.ui.screens.PitchAndSpeedScreen
import com.example.ui.screens.PresetsScreen
import com.example.ui.screens.ProjectsExportScreen
import com.example.ui.screens.RecordingStudioScreen
import com.example.ui.screens.ReverseAudioScreen
import com.example.ui.screens.RingtoneMakerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SmartMusicMixerScreen
import com.example.ui.screens.SpeechToTextScreen
import com.example.ui.screens.SurroundStereoScreen
import com.example.ui.screens.TextToSpeechStudioScreen
import com.example.ui.screens.VideoAudioEditorScreen
import com.example.ui.screens.VoicePurifierScreen
import com.example.ui.screens.VoiceTransformerScreen
import com.example.ui.theme.SonixTheme
import com.example.ui.theme.StudioColors
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
            SonixTheme {
                SonixRootApp()
            }
        }
        window.decorView.post {
            window.setBackgroundDrawable(ColorDrawable(0xFF070B14.toInt()))
        }
    }
}

@Composable
private fun SonixRootApp(
    viewModel: StudioViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lang = uiState.language
    val layoutDirection = if (lang.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    // Hoist Home LazyListState at parent composable level outside NavHost/conditionals
    // so scroll position stays intact across any tool navigation and return.
    val homeListState = rememberLazyListState()
    var sleepTimerDialogOpen by remember { mutableStateOf(false) }
    var askedNotificationOnce by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ -> }

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
                    val targetPkg = effect.targetPackage
                    if (!targetPkg.isNullOrBlank()) {
                        shareIntent.setPackage(targetPkg)
                        val launched = runCatching {
                            context.startActivity(shareIntent)
                            true
                        }.getOrDefault(false)
                        if (!launched) {
                            shareIntent.setPackage(null)
                            context.startActivity(Intent.createChooser(shareIntent, effect.title))
                        }
                    } else {
                        context.startActivity(Intent.createChooser(shareIntent, effect.title))
                    }
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
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDest = navBackStackEntry?.destination
                val isHomeTab = currentDest?.hasRoute<StudioRoute.Home>() == true
                val isLibraryTab = currentDest?.hasRoute<StudioRoute.AudioPicker>() == true &&
                    runCatching { navBackStackEntry?.toRoute<StudioRoute.AudioPicker>()?.target }.getOrNull() == AudioPickerTarget.LIBRARY_BROWSE
                val isProjectsTab = currentDest?.hasRoute<StudioRoute.ProjectsExport>() == true
                val showBottomNav = isHomeTab || isLibraryTab || isProjectsTab

                Scaffold(
                    containerColor = Color.Transparent,
                    bottomBar = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            PersistentTransportBar(
                                uiState = uiState,
                                onPlayPause = {
                                    if (!uiState.playback.isPlaying &&
                                        !askedNotificationOnce &&
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.POST_NOTIFICATIONS,
                                        ) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        askedNotificationOnce = true
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    viewModel.togglePlayPause()
                                },
                                onStop = { viewModel.stopPlayback() },
                                onSeekFraction = { viewModel.seekToFraction(it) },
                                onToggleAutoLoop = { viewModel.setAutoLoopOnPlayback(!uiState.autoLoopOnPlayback) },
                                onToggleBypass = { viewModel.toggleBypass() },
                                onHoldBypassChange = { viewModel.setHoldBypass(it) },
                                onOpenSleepTimer = { sleepTimerDialogOpen = true },
                                includeNavBarsPadding = !showBottomNav,
                            )

                            if (showBottomNav) {
                                NavigationBar(
                                    containerColor = StudioColors.CardSurface,
                                    contentColor = StudioColors.TextPrimary,
                                    modifier = Modifier.navigationBarsPadding(),
                                ) {
                                    val itemColors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = StudioColors.BgTop,
                                        selectedTextColor = StudioColors.Cyan,
                                        indicatorColor = StudioColors.Cyan,
                                        unselectedIconColor = StudioColors.TextSecondary,
                                        unselectedTextColor = StudioColors.TextSecondary,
                                    )

                                    NavigationBarItem(
                                        selected = isHomeTab,
                                        onClick = {
                                            navController.navigate(StudioRoute.Home) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = { Icon(Icons.Rounded.Tune, contentDescription = t(lang, "nav_tools")) },
                                        label = {
                                            Text(
                                                text = t(lang, "nav_tools"),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        colors = itemColors,
                                        modifier = Modifier.testTag("bottom_nav_tools"),
                                    )

                                    NavigationBarItem(
                                        selected = isLibraryTab,
                                        onClick = {
                                            navController.navigate(StudioRoute.AudioPicker(AudioPickerTarget.LIBRARY_BROWSE)) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = t(lang, "nav_library")) },
                                        label = {
                                            Text(
                                                text = t(lang, "nav_library"),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        colors = itemColors,
                                        modifier = Modifier.testTag("bottom_nav_library"),
                                    )

                                    NavigationBarItem(
                                        selected = isProjectsTab,
                                        onClick = {
                                            navController.navigate(StudioRoute.ProjectsExport) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = { Icon(Icons.Rounded.FolderOpen, contentDescription = t(lang, "nav_projects")) },
                                        label = {
                                            Text(
                                                text = t(lang, "nav_projects"),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        colors = itemColors,
                                        modifier = Modifier.testTag("bottom_nav_projects"),
                                    )
                                }
                            }
                        }
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
                                    listState = homeListState,
                                    onNavigate = { route ->
                                        navController.navigate(route) {
                                            launchSingleTop = true
                                        }
                                    },
                                )
                            }
                            composable<StudioRoute.AudioPicker> { entry ->
                                val dest = entry.toRoute<StudioRoute.AudioPicker>()
                                val isBrowseTab = dest.target == AudioPickerTarget.LIBRARY_BROWSE
                                InAppAudioPickerScreen(
                                    target = dest.target,
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    showBackButton = !isBrowseTab,
                                    onPickedComplete = {
                                        if (!isBrowseTab) {
                                            navController.popBackStack()
                                        }
                                    },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.Record> {
                                RecordingStudioScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onOpenAudioPicker = {
                                        navController.navigate(StudioRoute.AudioPicker(AudioPickerTarget.IMPORT_MAIN))
                                    },
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
                                    onOpenPicker = { target ->
                                        navController.navigate(StudioRoute.AudioPicker(target))
                                    },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.Presets> {
                                PresetsScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onOpenEchoSpace = { navController.navigate(StudioRoute.EchoSpace) },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.ProjectsExport> {
                                ProjectsExportScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onBack = {
                                        if (!navController.popBackStack()) {
                                            navController.navigate(StudioRoute.Home) {
                                                launchSingleTop = true
                                            }
                                        }
                                    },
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
                            composable<StudioRoute.AudioMerge> {
                                AudioMergeScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onOpenPicker = { target -> navController.navigate(StudioRoute.AudioPicker(target)) },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.AudioSplit> {
                                AudioSplitScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onOpenPicker = { target -> navController.navigate(StudioRoute.AudioPicker(target)) },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.PitchAndSpeed> {
                                PitchAndSpeedScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onOpenPicker = { target -> navController.navigate(StudioRoute.AudioPicker(target)) },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.ReverseAudio> {
                                ReverseAudioScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onOpenPicker = { target -> navController.navigate(StudioRoute.AudioPicker(target)) },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.SurroundStereo> {
                                SurroundStereoScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onOpenPicker = { target -> navController.navigate(StudioRoute.AudioPicker(target)) },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.GenerateSilence> {
                                GenerateSilenceScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.FormatConverter> {
                                FormatConverterScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onOpenPicker = { target -> navController.navigate(StudioRoute.AudioPicker(target)) },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.ExtractVideoAudio> {
                                ExtractVideoAudioScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.VideoAudioEditor> {
                                VideoAudioEditorScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onOpenPicker = { target -> navController.navigate(StudioRoute.AudioPicker(target)) },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.CompressAudio> {
                                CompressAudioScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onOpenPicker = { target -> navController.navigate(StudioRoute.AudioPicker(target)) },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.RingtoneMaker> {
                                RingtoneMakerScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onOpenPicker = { target -> navController.navigate(StudioRoute.AudioPicker(target)) },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.SpeechToText> {
                                SpeechToTextScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onOpenPicker = { target -> navController.navigate(StudioRoute.AudioPicker(target)) },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.SmartMusicMixer> {
                                SmartMusicMixerScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onOpenPicker = { target -> navController.navigate(StudioRoute.AudioPicker(target)) },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                        }
                    }
                }

                SleepTimerDialog(
                    open = sleepTimerDialogOpen,
                    language = lang,
                    selectedOption = uiState.sleepTimerOption,
                    remainingSec = uiState.sleepTimerRemainingSec,
                    onSelect = { viewModel.setSleepTimer(it) },
                    onDismiss = { sleepTimerDialogOpen = false },
                )
            }
        }
    }
}
