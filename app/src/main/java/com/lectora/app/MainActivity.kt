package com.lectora.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lectora.app.data.LibraryScanWorker
import com.lectora.app.playback.PlaybackConnection
import com.lectora.app.playback.PlayerActions
import com.lectora.app.ui.library.CollectionScreen
import com.lectora.app.ui.library.LibraryScreen
import com.lectora.app.ui.library.LibraryViewModel
import com.lectora.app.ui.player.MiniPlayer
import com.lectora.app.ui.reader.ReaderScreen
import com.lectora.app.ui.reader.ReaderViewModel
import com.lectora.app.ui.settings.SettingsScreen
import com.lectora.app.ui.settings.SettingsViewModel
import com.lectora.app.ui.theme.LectoraTheme
import com.lectora.narration.PlaybackService

class MainActivity : ComponentActivity() {
    private lateinit var connection: PlaybackConnection
    private lateinit var player: PlayerActions
    private var openPlayerRequest by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LectoraApp).container
        connection = PlaybackConnection(applicationContext, container.narration)
        player = PlayerActions(lifecycleScope, connection, container.narration, container.settings)
        if (savedInstanceState == null) {
            LibraryScanWorker.runNow(this)
            container.narration.warmUp()
        }
        handleIntent(intent)

        setContent {
            val titleSource by container.settings.titleSource.collectAsState(initial = com.lectora.app.data.TitleSource.FILE_NAME)
            LectoraTheme {
                androidx.compose.runtime.CompositionLocalProvider(com.lectora.app.ui.components.LocalTitleSource provides titleSource) {
                    NotificationPermission()
                    LectoraNavHost(container, player, openPlayerRequest)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connection.connect()
    }

    override fun onStop() {
        // Desconectar no detiene la narración: el servicio sigue en primer plano mientras suena.
        connection.disconnect()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(PlaybackService.EXTRA_OPEN_PLAYER, false) == true) openPlayerRequest++
    }
}

@Composable
private fun NotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}

@Composable
private fun LectoraNavHost(container: AppContainer, player: PlayerActions, openPlayerRequest: Int) {
    val nav = rememberNavController()
    val narration by player.state.collectAsState()
    val libraryVm: LibraryViewModel = viewModel(factory = viewModelFactory { initializer { LibraryViewModel(container) } })

    // Tocar la notificación abre el libro que se está narrando.
    var handledRequest by remember { mutableStateOf(0) }
    LaunchedEffect(openPlayerRequest, narration.bookId) {
        val id = narration.bookId
        if (openPlayerRequest > handledRequest && id != null) {
            handledRequest = openPlayerRequest
            nav.navigate("reader/$id") { launchSingleTop = true }
        }
    }

    val mini: @Composable () -> Unit = {
        MiniPlayer(
            narration,
            onClick = { narration.bookId?.let { nav.navigate("reader/$it") { launchSingleTop = true } } },
            onToggle = player::toggle,
        )
    }

    NavHost(nav, startDestination = "library") {
        composable("library") {
            LibraryScreen(
                vm = libraryVm,
                miniPlayer = mini,
                onOpenBook = { nav.navigate("reader/$it") },
                onOpenCollection = { nav.navigate("collection/$it") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("collection/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments!!.getLong("id")
            CollectionScreen(libraryVm, id, mini, onBack = { nav.popBackStack() }, onOpenBook = { nav.navigate("reader/$it") })
        }
        composable("reader/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments!!.getLong("id")
            val vm: ReaderViewModel = viewModel(factory = viewModelFactory { initializer { ReaderViewModel(container, id) } })
            ReaderScreen(
                vm = vm,
                bookId = id,
                player = player,
                onBack = { nav.popBackStack() },
                onOpenTranslationSettings = { nav.navigate("settings") },
            )
        }
        composable("settings") {
            val vm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(container) } })
            SettingsScreen(vm, onBack = { nav.popBackStack() })
        }
    }
}
