package com.julienmiquel.audiotheque.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.julienmiquel.audiotheque.AppContainer
import com.julienmiquel.audiotheque.AuthRenewal
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.displayTitle
import com.julienmiquel.audiotheque.core.model.effectiveStatus
import com.julienmiquel.audiotheque.library.Gesture
import com.julienmiquel.audiotheque.player.PlaybackEvent
import com.julienmiquel.audiotheque.player.PlayerRuntime
import com.julienmiquel.audiotheque.ui.book.BookScreen
import com.julienmiquel.audiotheque.ui.common.Labels
import com.julienmiquel.audiotheque.ui.common.LocalAppContainer
import com.julienmiquel.audiotheque.ui.library.LibraryScreen
import com.julienmiquel.audiotheque.ui.onboarding.OnboardingScreen
import com.julienmiquel.audiotheque.ui.player.PlayerScreen
import com.julienmiquel.audiotheque.ui.settings.SettingsScreen
import com.julienmiquel.audiotheque.ui.sync.SyncScreen
import com.julienmiquel.audiotheque.ui.theme.AudiothequeTheme
import com.julienmiquel.audiotheque.ui.theme.isDarkTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data object LibraryDest

@Serializable
data object PlayerDest

@Serializable
data object SyncDest

@Serializable
data object SettingsDest

@Serializable
data class BookDest(val bookId: String)

private enum class TopTab(val label: String, val icon: ImageVector, val route: Any) {
    LIBRARY("Bibliothèque", Icons.AutoMirrored.Filled.LibraryBooks, LibraryDest),
    PLAYER("Lecteur", Icons.Filled.Headphones, PlayerDest),
    SYNC("Synchro", Icons.Filled.Sync, SyncDest),
}

/** Racine de l'interface : thème, accueil ou navigation à trois onglets. */
@Composable
fun AudiothequeRoot(container: AppContainer, openPlayerRequests: Flow<Unit>) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val s = settings
    val dark = isDarkTheme(s?.theme ?: com.julienmiquel.audiotheque.core.settings.ThemeMode.DARK)
    val activity = LocalActivity.current as? ComponentActivity
    LaunchedEffect(dark) {
        val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
    AudiothequeTheme(s?.theme ?: com.julienmiquel.audiotheque.core.settings.ThemeMode.DARK) {
        androidx.compose.runtime.CompositionLocalProvider(LocalAppContainer provides container) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                when {
                    s == null -> Box(Modifier.fillMaxSize())
                    !s.onboardingDone || !s.isConnected -> OnboardingScreen()
                    else -> MainScaffold(container, openPlayerRequests)
                }
            }
        }
    }
}

@Composable
private fun MainScaffold(container: AppContainer, openPlayerRequests: Flow<Unit>) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    var lastTab by rememberSaveable { mutableStateOf(TopTab.LIBRARY) }
    val currentTab = TopTab.entries.firstOrNull { tab -> destination?.hasRoute(tab.route::class) == true }
        ?: if (destination?.hasRoute(BookDest::class) == true) TopTab.LIBRARY else lastTab
    LaunchedEffect(currentTab) { lastTab = currentTab }
    val engine by container.engine.status.collectAsStateWithLifecycle()
    val authRequired by container.authRequired.collectAsStateWithLifecycle(initialValue = false)
    var finishedBook by remember { mutableStateOf<Pair<String, String>?>(null) }

    NotificationPermission()

    // Bandeau « Annuler » : 10 s après chaque geste de statut.
    LaunchedEffect(Unit) {
        container.moves.collectLatest { move ->
            val result = snackbar.showSnackbar(
                message = "« ${move.title} » → ${Labels.statusTab(move.to)}",
                actionLabel = "Annuler",
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) container.undoMove(move)
        }
    }
    LaunchedEffect(Unit) {
        container.messages.collect { snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Short) }
    }
    LaunchedEffect(Unit) {
        PlayerRuntime.events.collect { event ->
            when (event) {
                is PlaybackEvent.BookFinished -> {
                    val book = container.library.book(event.bookId)
                    if (book != null && book.effectiveStatus != BookStatus.DONE && book.effectiveStatus != BookStatus.ARCHIVED) {
                        finishedBook = book.driveId to book.displayTitle
                    }
                }
                is PlaybackEvent.WaitingForDownload ->
                    snackbar.showSnackbar("Piste suivante pas encore téléchargée : la lecture reprendra à son arrivée.", duration = SnackbarDuration.Short)
            }
        }
    }
    LaunchedEffect(Unit) { openPlayerRequests.collect { nav.navigateToTab(TopTab.PLAYER) } }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.imePadding()) },
        bottomBar = {
            Column {
                if (authRequired) AuthRenewalBanner(container)
                NavigationBar {
                    TopTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { nav.navigateToTab(tab) },
                            icon = {
                                if (tab == TopTab.SYNC && engine.running) {
                                    BadgedBox(badge = { Badge(Modifier.semantics { contentDescription = "Synchro en cours" }) }) {
                                        Icon(tab.icon, contentDescription = null)
                                    }
                                } else {
                                    Icon(tab.icon, contentDescription = null)
                                }
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        AppNavHost(nav, padding)
    }

    finishedBook?.let { (bookId, title) ->
        AlertDialog(
            onDismissRequest = { finishedBook = null },
            title = { Text("Livre terminé") },
            text = { Text("Vous avez écouté « $title » jusqu'au bout. Le marquer comme écouté et le ranger dans _Ecouté ?") },
            confirmButton = {
                TextButton(onClick = {
                    container.applyGesture(bookId, Gesture.MARQUER_ECOUTE)
                    finishedBook = null
                }) { Text("Marquer comme écouté") }
            },
            dismissButton = { TextButton(onClick = { finishedBook = null }) { Text("Pas maintenant") } },
        )
    }
}

@Composable
private fun AppNavHost(nav: NavHostController, padding: PaddingValues) {
    NavHost(navController = nav, startDestination = LibraryDest) {
        composable<LibraryDest> {
            LibraryScreen(
                onOpenBook = { nav.navigate(BookDest(it)) },
                onOpenPlayer = { nav.navigateToTab(TopTab.PLAYER) },
                onOpenSettings = { nav.navigate(SettingsDest) { launchSingleTop = true } },
                contentPadding = padding,
            )
        }
        composable<PlayerDest> {
            PlayerScreen(
                onOpenBook = { nav.navigate(BookDest(it)) },
                onOpenLibrary = { nav.navigateToTab(TopTab.LIBRARY) },
                contentPadding = padding,
            )
        }
        composable<SyncDest> {
            SyncScreen(onOpenSettings = { nav.navigate(SettingsDest) { launchSingleTop = true } }, contentPadding = padding)
        }
        composable<SettingsDest> {
            SettingsScreen(onBack = { nav.popBackStack() }, contentPadding = padding)
        }
        composable<BookDest> { entry ->
            val route = entry.toRoute<BookDest>()
            BookScreen(
                bookId = route.bookId,
                onBack = { nav.popBackStack() },
                onOpenBook = { nav.navigate(BookDest(it)) },
                onOpenPlayer = { nav.navigateToTab(TopTab.PLAYER) },
                contentPadding = padding,
            )
        }
    }
}

private fun NavHostController.navigateToTab(tab: TopTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Autorisation Google Drive refusée ou expirée : synchro en pause (lecture et gestes restent possibles,
 * les déplacements attendent en file) jusqu'au renouvellement, fait sans quitter l'app.
 */
@Composable
private fun AuthRenewalBanner(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    fun report(renewal: AuthRenewal) {
        if (renewal is AuthRenewal.Failed) container.message(renewal.message)
        busy = false
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        scope.launch { report(container.completeRenewal(result.resultCode == Activity.RESULT_OK, result.data)) }
    }

    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.SyncProblem, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                "Autorisation Google Drive à renouveler : synchro en pause.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        when (val renewal = container.renewAuthorization()) {
                            is AuthRenewal.NeedsConsent ->
                                runCatching { consent.launch(IntentSenderRequest.Builder(renewal.pendingIntent.intentSender).build()) }
                                    .onFailure { report(AuthRenewal.Failed("Écran d'autorisation Google indisponible : réessayez.")) }
                            else -> report(renewal)
                        }
                    }
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("Renouveler") }
        }
    }
}

/** Android 13+ : notifications de lecture et de synchro (demandé une fois). */
@Composable
private fun NotificationPermission() {
    val activity = LocalActivity.current ?: return
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted && !asked) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
