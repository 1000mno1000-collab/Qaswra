package com.example

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.diagnostics.AppDiagnosticLog
import com.example.i18n.t
import com.example.model.AudioPickerTarget
import com.example.model.AuthSession
import com.example.model.EffectType
import com.example.model.StudioRoute
import com.example.studio.ui.applyDisplayBoost
import com.example.studio.ui.lit
import com.example.ui.components.PersistentTransportBar
import com.example.ui.components.SleepTimerDialog
import com.example.ui.components.SonixLuxuryLaunchOverlay
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
import com.example.ui.screens.NotificationDetailScreen
import com.example.ui.screens.NotificationsListScreen
import com.example.ui.screens.PitchAndSpeedScreen
import com.example.ui.screens.PresetsScreen
import com.example.ui.screens.ProjectsExportScreen
import com.example.ui.screens.RecordingStudioScreen
import com.example.ui.screens.ReelsAudioTemplatesScreen
import com.example.ui.screens.ReverseAudioScreen
import com.example.ui.screens.RingtoneMakerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SmartAssistantScreen
import com.example.ui.screens.SmartMusicMixerScreen
import com.example.ui.screens.SpeechToTextScreen
import com.example.ui.screens.SurroundStereoScreen
import com.example.ui.screens.TextToSpeechStudioScreen
import com.example.ui.screens.VoicePurifierScreen
import com.example.ui.screens.VoiceTransformerScreen
import com.example.ui.theme.SonixTheme
import com.example.ui.theme.StudioColors
import com.example.viewmodel.StudioSideEffect
import com.example.viewmodel.StudioViewModel
import com.google.firebase.FirebaseApp

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
        applyDisplayBoost()

        try {
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                SonixCrashGuard.handleUncaughtException(applicationContext, thread, throwable)
            }
        } catch (t: Throwable) {
            Log.e("MainActivity", "Failed to register uncaught exception handler", t)
        }

        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }
            SonixCrashGuard.safeInitializeFirebase(this)
        } catch (t: Throwable) {
            Log.w("MainActivity", "Firebase startup initialization skipped safely: ${t.message}")
        }

        try {
            AppDiagnosticLog.init(this)
            val askRestartConsent = intent?.getBooleanExtra(SonixCrashGuard.EXTRA_ASK_RESTART_CONSENT, false) == true
            val diagId = intent?.getStringExtra(SonixCrashGuard.EXTRA_DIAGNOSTIC_ID)
            val crashMsg = intent?.getStringExtra(SonixCrashGuard.EXTRA_CRASH_MESSAGE)
            if (askRestartConsent) {
                if (!diagId.isNullOrBlank()) {
                    AppDiagnosticLog.showPromptForEntryId(diagId)
                }
            } else if (!crashMsg.isNullOrBlank()) {
                SonixCrashGuard.reportError(
                    StartupErrorReport(
                        threadName = intent.getStringExtra(SonixCrashGuard.EXTRA_CRASH_THREAD) ?: "main",
                        message = crashMsg,
                        stackTrace = intent.getStringExtra(SonixCrashGuard.EXTRA_CRASH_TRACE) ?: crashMsg,
                    ),
                )
            }
        } catch (t: Throwable) {
            Log.w("MainActivity", "Failed reading crash intent extras", t)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                splashScreen.setOnExitAnimationListener { splashView ->
                    runCatching {
                        splashView.animate()
                            .alpha(0f)
                            .setDuration(240L)
                            .withEndAction { splashView.remove() }
                            .start()
                    }.onFailure {
                        runCatching { splashView.remove() }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w("MainActivity", "Splash exit animation skipped safely: ${t.message}")
        }

        try {
            enableEdgeToEdge()
        } catch (t: Throwable) {
            Log.w("MainActivity", "enableEdgeToEdge failed safely: ${t.message}")
        }

        val studioViewModel: StudioViewModel? = try {
            ViewModelProvider(this)[StudioViewModel::class.java]
        } catch (t: Throwable) {
            Log.e("MainActivity", "StudioViewModel startup initialization failed", t)
            SonixCrashGuard.reportError(Thread.currentThread().name, t)
            null
        }

        try {
            setContent {
                SonixTheme {
                    val activeCrash by SonixCrashGuard.uncaughtError.collectAsStateWithLifecycle()
                    val errorReport = activeCrash
                    if (errorReport != null || studioViewModel == null) {
                        StartupErrorScreen(
                            report = errorReport ?: StartupErrorReport(
                                threadName = "main",
                                message = "Unable to initialize Sonix Studio components.",
                                stackTrace = "StudioViewModel initialization returned null.",
                            ),
                            onRetry = {
                                SonixCrashGuard.clearError()
                                val restartIntent = Intent(this, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                }
                                startActivity(restartIntent)
                                finish()
                            },
                        )
                    } else {
                        SonixRootApp(viewModel = studioViewModel)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("MainActivity", "setContent failed during startup", t)
            SonixCrashGuard.reportError(Thread.currentThread().name, t)
            runCatching {
                setContent {
                    SonixTheme {
                        StartupErrorScreen(
                            report = StartupErrorReport(
                                threadName = Thread.currentThread().name,
                                message = t.localizedMessage ?: t::class.java.simpleName,
                                stackTrace = Log.getStackTraceString(t),
                            ),
                            onRetry = {
                                SonixCrashGuard.clearError()
                                recreate()
                            },
                        )
                    }
                }
            }
        }

        try {
            window.decorView.post {
                runCatching {
                    window.setBackgroundDrawable(ColorDrawable(0xFF070B14.toInt()))
                }
            }
        } catch (t: Throwable) {
            Log.w("MainActivity", "Background drawable post skipped safely: ${t.message}")
        }
    }
}

@Composable
private fun SonixRootApp(
    viewModel: StudioViewModel,
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
    var showLuxuryLaunch by rememberSaveable { mutableStateOf(true) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ -> }

    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = CreateTypedDocumentContract(),
    ) { uri ->
        viewModel.completeSaveExportedDocument(uri)
    }

    val openDocumentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        viewModel.onDefaultSaveFolderPicked(treeUri)
    }

    LaunchedEffect(viewModel) {
        viewModel.sideEffects.collect { effect ->
            when (effect) {
                is StudioSideEffect.PromptSaveDocument -> {
                    saveDocumentLauncher.launch(effect.result.mimeType to effect.suggestedName)
                }
                is StudioSideEffect.PromptPickSaveFolderOnce -> {
                    openDocumentTreeLauncher.launch(null)
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
                is StudioSideEffect.LaunchMultiShareIntent -> {
                    val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = effect.mimeType
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(effect.uris))
                        putExtra(Intent.EXTRA_SUBJECT, effect.title)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, effect.title))
                }
            }
        }
    }

    val bgBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                StudioColors.BgTop,
                StudioColors.BgMid,
                StudioColors.BgTop,
            ),
        )
    }

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgBrush),
        ) {
            if (uiState.authSession is AuthSession.SignedOut) {
                AuthScreen(
                    uiState = uiState,
                    viewModel = viewModel,
                    onLoginSuccess = {
                        viewModel.clearBanner()
                    },
                )
            } else {
                val uidKey = (uiState.authSession as? AuthSession.SignedIn)?.uid ?: "guest"
                key(uidKey) {
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

                                    val toolsInteraction = remember { MutableInteractionSource() }
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
                                        interactionSource = toolsInteraction,
                                        icon = { Icon(Icons.Rounded.Tune, contentDescription = t(lang, "nav_tools")) },
                                        label = {
                                            Text(
                                                text = t(lang, "nav_tools"),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        colors = itemColors,
                                        modifier = Modifier
                                            .lit(toolsInteraction, MaterialTheme.colorScheme.primary, selected = isHomeTab, cornerRadius = 16.dp)
                                            .testTag("bottom_nav_tools"),
                                    )

                                    val libraryInteraction = remember { MutableInteractionSource() }
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
                                        interactionSource = libraryInteraction,
                                        icon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = t(lang, "nav_library")) },
                                        label = {
                                            Text(
                                                text = t(lang, "nav_library"),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        colors = itemColors,
                                        modifier = Modifier
                                            .lit(libraryInteraction, MaterialTheme.colorScheme.primary, selected = isLibraryTab, cornerRadius = 16.dp)
                                            .testTag("bottom_nav_library"),
                                    )

                                    val projectsInteraction = remember { MutableInteractionSource() }
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
                                        interactionSource = projectsInteraction,
                                        icon = { Icon(Icons.Rounded.FolderOpen, contentDescription = t(lang, "nav_projects")) },
                                        label = {
                                            Text(
                                                text = t(lang, "nav_projects"),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        colors = itemColors,
                                        modifier = Modifier
                                            .lit(projectsInteraction, MaterialTheme.colorScheme.primary, selected = isProjectsTab, cornerRadius = 16.dp)
                                            .testTag("bottom_nav_projects"),
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
                                        val dismissInteraction = remember { MutableInteractionSource() }
                                        IconButton(
                                            onClick = { viewModel.clearBanner() },
                                            interactionSource = dismissInteraction,
                                            modifier = Modifier
                                                .size(48.dp)
                                                .lit(dismissInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 24.dp)
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

                        uiState.importProgress?.let { progress ->
                            val pct = (progress.coerceIn(0f, 1f) * 100f).toInt()
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = StudioColors.CardSurface,
                                    border = BorderStroke(1.dp, StudioColors.Violet.copy(alpha = 0.55f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .widthIn(max = 640.dp)
                                        .testTag("global_import_progress_card"),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = "${t(lang, "importing_file_progress")} $pct%",
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                color = StudioColors.Cyan,
                                                modifier = Modifier.weight(1f),
                                            )
                                            val detailText = listOfNotNull(
                                                uiState.importBytesLabel?.takeIf { it.isNotBlank() },
                                                uiState.importLabel?.takeIf { it.isNotBlank() },
                                            ).joinToString(" • ")
                                            if (detailText.isNotBlank()) {
                                                Text(
                                                    text = detailText,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = StudioColors.TextSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.widthIn(max = 200.dp),
                                                )
                                            }
                                        }
                                        LinearProgressIndicator(
                                            progress = { progress.coerceIn(0f, 1f) },
                                            color = StudioColors.Cyan,
                                            trackColor = StudioColors.BgTop,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(7.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .testTag("global_import_progress_bar"),
                                        )
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
                                    onOpenNotifications = {
                                        navController.navigate(StudioRoute.Notifications) {
                                            launchSingleTop = true
                                        }
                                    },
                                    onRestoreRecentEdit = { entry ->
                                        viewModel.restoreRecentEditSession(entry) { route ->
                                            navController.navigate(route) {
                                                launchSingleTop = true
                                            }
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
                                    onRouteToExtractVideo = {
                                        navController.navigate(StudioRoute.ExtractVideoAudio) {
                                            launchSingleTop = true
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
                                    onChangeDefaultSaveFolder = {
                                        openDocumentTreeLauncher.launch(null)
                                    },
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
                            composable<StudioRoute.SmartAssistant> {
                                SmartAssistantScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onNavigateToTool = { toolId ->
                                        val dest: StudioRoute = when (toolId) {
                                            "noise_reduction" -> StudioRoute.VoicePurifier
                                            "voice_changer" -> StudioRoute.VoiceTransformer
                                            "human_tts" -> StudioRoute.TtsStudio
                                            "reels_audio_templates" -> StudioRoute.ReelsAudioTemplates
                                            "extract_video" -> StudioRoute.ExtractVideoAudio
                                            "surround_stereo" -> StudioRoute.SurroundStereo
                                            "equalizer" -> StudioRoute.EffectDetail(EffectType.EQUALIZER)
                                            "pitch_speed" -> StudioRoute.PitchAndSpeed
                                            "smart_mixer" -> StudioRoute.SmartMusicMixer
                                            "audio_editor" -> StudioRoute.Editor
                                            "presets_echo" -> StudioRoute.Presets
                                            else -> StudioRoute.Home
                                        }
                                        navController.navigate(dest) {
                                            launchSingleTop = true
                                        }
                                    },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.ReelsAudioTemplates> {
                                ReelsAudioTemplatesScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onOpenAudioPicker = { target ->
                                        navController.navigate(StudioRoute.AudioPicker(target))
                                    },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.Notifications> {
                                NotificationsListScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onOpenDetail = { entryId ->
                                        navController.navigate(StudioRoute.NotificationDetail(entryId))
                                    },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable<StudioRoute.NotificationDetail> { entry ->
                                val dest = entry.toRoute<StudioRoute.NotificationDetail>()
                                NotificationDetailScreen(
                                    entryId = dest.entryId,
                                    uiState = uiState,
                                    viewModel = viewModel,
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

            val criticalPrompt by AppDiagnosticLog.criticalRestartPrompt.collectAsStateWithLifecycle()
            criticalPrompt?.let { promptEntry ->
                AlertDialog(
                    onDismissRequest = { AppDiagnosticLog.dismissCriticalPrompt() },
                    containerColor = StudioColors.CardSurface,
                    titleContentColor = StudioColors.TextPrimary,
                    textContentColor = StudioColors.TextSecondary,
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = StudioColors.RoseBright,
                            )
                            Text(
                                text = t(lang, "crash_dialog_title"),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            )
                        }
                    },
                    text = {
                        Text(
                            text = "${t(lang, "crash_dialog_body")}\n\n[${promptEntry.errorCode}] ${promptEntry.causeAr}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                AppDiagnosticLog.dismissCriticalPrompt()
                                val restartIntent = Intent(context, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                }
                                context.startActivity(restartIntent)
                                (context as? Activity)?.finish()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StudioColors.RoseBright,
                                contentColor = StudioColors.BgTop,
                            ),
                            modifier = Modifier.testTag("crash_restart_now_button"),
                        ) {
                            Text(t(lang, "crash_dialog_restart_now"))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { AppDiagnosticLog.dismissCriticalPrompt() },
                            modifier = Modifier.testTag("crash_stay_retry_button"),
                        ) {
                            Text(
                                text = t(lang, "crash_dialog_stay_retry"),
                                color = StudioColors.Cyan,
                            )
                        }
                    },
                )
            }

            SonixLuxuryLaunchOverlay(
                visible = showLuxuryLaunch,
                language = lang,
                onFinished = { showLuxuryLaunch = false },
            )
        }
    }
}

@Composable
private fun StartupErrorScreen(
    report: StartupErrorReport,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    val bgBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                StudioColors.BgTop,
                StudioColors.BgMid,
                StudioColors.BgTop,
            ),
        )
    }
    val logoBorderBrush = remember {
        Brush.linearGradient(listOf(StudioColors.Cyan, StudioColors.Violet))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFFFFFFFE),
                border = BorderStroke(
                    width = 1.5.dp,
                    brush = logoBorderBrush,
                ),
                shadowElevation = 10.dp,
                modifier = Modifier.size(72.dp),
            ) {
                Image(
                    bitmap = ImageBitmap.imageResource(R.drawable.sonix_primary_logo),
                    contentDescription = "Sonix ✦",
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(20.dp)),
                )
            }

            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = StudioColors.CardSurface),
                border = BorderStroke(1.dp, StudioColors.Rose.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = StudioColors.RoseBright,
                        modifier = Modifier.size(36.dp),
                    )
                    Text(
                        text = "Sonix ✦ Safe Recovery",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = StudioColors.TextPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "An unexpected startup or runtime error was intercepted so the studio did not crash. Local audio tools remain safe to retry.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioColors.TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "تم اعتراض خطأ غير متوقع بأمان لمنع توقف التطبيق. يمكنك إعادة تشغيل الاستوديو أو نسخ تفاصيل الخطأ.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextSecondary,
                        textAlign = TextAlign.Center,
                    )

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = StudioColors.BgTop,
                        border = BorderStroke(1.dp, StudioColors.Border),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 90.dp, max = 220.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "[${report.threadName}] ${report.message}",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = StudioColors.RoseBright,
                            )
                            Text(
                                text = report.stackTrace,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = StudioColors.TextMuted,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val copyInteraction = remember { MutableInteractionSource() }
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cm?.setPrimaryClip(
                                        ClipData.newPlainText(
                                            "Sonix Error",
                                            "[${report.threadName}] ${report.message}\n\n${report.stackTrace}",
                                        ),
                                    )
                                    copied = true
                                }
                            },
                            interactionSource = copyInteraction,
                            border = BorderStroke(1.dp, StudioColors.BorderBright),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .lit(copyInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                                .testTag("error_copy_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = null,
                                tint = StudioColors.Cyan,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (copied) "Copied" else "Copy Details",
                                color = StudioColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        val retryInteraction = remember { MutableInteractionSource() }
                        Button(
                            onClick = onRetry,
                            interactionSource = retryInteraction,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StudioColors.Cyan,
                                contentColor = StudioColors.BgTop,
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .lit(retryInteraction, MaterialTheme.colorScheme.primary, cornerRadius = 14.dp)
                                .testTag("error_retry_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Retry Launch",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

