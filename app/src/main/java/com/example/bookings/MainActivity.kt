package com.example.bookings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.example.bookings.telegram.MonitorStatus
import com.example.bookings.ui.AgendaScreen
import com.example.bookings.ui.DetailScreen
import com.example.bookings.ui.RequestsScreen
import com.example.bookings.ui.ScheduleScreen
import com.example.bookings.ui.SettingsScreen

private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

sealed interface Screen {
    data object Requests : Screen
    data object Agenda : Screen
    data object Schedule : Screen
    data object Settings : Screen
    data class Detail(val id: Long) : Screen
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm.resumeIfEnabled()
        // Layout direction follows the device language (Arabic => right-to-left automatically).
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0F766E))) {
                AppRoot(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel) {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Requests) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = screen is Screen.Detail) { screen = Screen.Requests }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(XLSX_MIME)
    ) { uri -> if (uri != null) vm.export(uri, onlyShown = true) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.startMonitoring() }   // monitoring works even if notifications are hidden

    val toggleMonitoring = {
        val s = vm.status.value
        val running = s is MonitorStatus.Starting || s is MonitorStatus.Connected || s is MonitorStatus.Reconnecting
        if (running) vm.stopMonitoring()
        else if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else vm.startMonitoring()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            when (screen) {
                                Screen.Requests -> R.string.nav_requests
                                Screen.Agenda -> R.string.nav_agenda
                                Screen.Schedule -> R.string.nav_schedule
                                Screen.Settings -> R.string.nav_settings
                                is Screen.Detail -> R.string.title_details
                            }
                        )
                    )
                },
                navigationIcon = {
                    if (screen is Screen.Detail) TextButton(onClick = { screen = Screen.Requests }) { Text(stringResource(R.string.back)) }
                },
            )
        },
        bottomBar = {
            if (screen !is Screen.Detail) NavigationBar {
                NavigationBarItem(screen is Screen.Requests, { screen = Screen.Requests },
                    { Icon(Icons.Filled.Notifications, null) }, label = { Text(stringResource(R.string.nav_requests)) })
                NavigationBarItem(screen is Screen.Agenda, { screen = Screen.Agenda },
                    { Icon(Icons.Filled.DateRange, null) }, label = { Text(stringResource(R.string.nav_agenda)) })
                NavigationBarItem(screen is Screen.Schedule, { screen = Screen.Schedule },
                    { Icon(Icons.Filled.Build, null) }, label = { Text(stringResource(R.string.nav_schedule)) })
                NavigationBarItem(screen is Screen.Settings, { screen = Screen.Settings },
                    { Icon(Icons.Filled.Settings, null) }, label = { Text(stringResource(R.string.nav_settings)) })
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        val open: (Long) -> Unit = { screen = Screen.Detail(it) }
        when (val s = screen) {
            Screen.Requests -> RequestsScreen(
                vm, open, toggleMonitoring,
                { exportLauncher.launch(vm.suggestedFileName()) },
                { vm.lastExport.value?.let { shareFile(ctx, it) } },
                m,
            )
            Screen.Agenda -> AgendaScreen(vm, open, m)
            Screen.Schedule -> ScheduleScreen(vm, m)
            Screen.Settings -> SettingsScreen(vm, toggleMonitoring, m)
            is Screen.Detail -> DetailScreen(vm, s.id, { screen = Screen.Requests }, m)
        }
    }
}

private fun shareFile(ctx: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = XLSX_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.share_export)))
}
