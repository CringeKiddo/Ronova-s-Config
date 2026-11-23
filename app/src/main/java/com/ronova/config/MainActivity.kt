package com.ronova.config

import android.content.Context
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ronova.config.data.ConfigState
import com.ronova.config.service.LatencyGuardService
import com.ronova.config.ui.MainViewModel
import com.ronova.config.ui.PermissionHelper
import com.ronova.config.ui.components.ConfigureSheet
import com.ronova.config.ui.components.PermissionsDialog
import com.ronova.config.ui.components.SettingsBlurDrawer
import com.ronova.config.ui.screens.AdbOnboardingScreen
import com.ronova.config.ui.screens.MainDashboardScreen
import com.ronova.config.ui.theme.RonovaPalette
import com.ronova.config.ui.theme.RonovaTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS = "ronova_ui"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_RUNTIME_ASKED = "runtime_permissions_asked"
    }

    private val vm: MainViewModel by viewModels()
    private var refreshTick by mutableIntStateOf(0)
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val runtimePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshTick++
        }

    /** The VPN consent dialog only grants permission; the engine starts either way and reports a skipped sinkhole. */
    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            LatencyGuardService.enable(this@MainActivity)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RonovaApp() }
    }

    override fun onResume() {
        super.onResume()
        refreshTick++
        vm.refreshAdbState()
    }

    private fun requestRuntimePermissions() {
        val missing = PermissionHelper.missingRuntimePermissions(this)
        if (missing.isNotEmpty()) runtimePermissions.launch(missing) else refreshTick++
    }

    private fun openDeveloperOptions() {
        if (!PermissionHelper.safeStart(this, PermissionHelper.developerOptionsIntent())) {
            PermissionHelper.safeStart(this, PermissionHelper.deviceInfoIntent())
        }
    }

    private fun startEngine(config: ConfigState) {
        val needsVpn = config.networkShield.enabled && config.networkShield.vpnPacketSinkhole
        val consent = if (needsVpn) VpnService.prepare(this) else null
        if (consent != null) vpnConsent.launch(consent) else LatencyGuardService.enable(this)
    }

    private fun finishOnboarding() {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
    }

    @Composable
    private fun RonovaApp() {
        val config by vm.config.collectAsStateWithLifecycle()
        val runtime by vm.runtime.collectAsStateWithLifecycle()
        val message by vm.message.collectAsStateWithLifecycle()
        val discovery by vm.discovery.collectAsStateWithLifecycle()
        val connection by vm.connection.collectAsStateWithLifecycle()
        val adbReady by vm.adbReady.collectAsStateWithLifecycle()
        val pairing by vm.pairing.collectAsStateWithLifecycle()
        val pendingChanges by vm.pendingChanges.collectAsStateWithLifecycle()
        val apps by vm.apps.collectAsStateWithLifecycle()
        val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()

        val firstRun = remember { !prefs.getBoolean(KEY_ONBOARDING_DONE, false) }
        var showOnboarding by rememberSaveable { mutableStateOf(firstRun) }
        var showPermissions by rememberSaveable { mutableStateOf(firstRun) }
        var showConfigure by rememberSaveable { mutableStateOf(false) }
        var showSettings by rememberSaveable { mutableStateOf(false) }

        val tick = refreshTick
        val permissionStatus = remember(tick) { PermissionHelper.status(this@MainActivity) }

        LaunchedEffect(Unit) {
            if (firstRun && !prefs.getBoolean(KEY_RUNTIME_ASKED, false)) {
                prefs.edit().putBoolean(KEY_RUNTIME_ASKED, true).apply()
                requestRuntimePermissions()
            }
        }
        LaunchedEffect(showOnboarding) {
            if (showOnboarding) vm.startDiscovery()
        }
        DisposableEffect(showConfigure) {
            vm.setDiagnosticsActive(showConfigure)
            onDispose { vm.setDiagnosticsActive(false) }
        }

        RonovaTheme(config.theme) {
            Box(Modifier.fillMaxSize()) {
                Crossfade(targetState = showOnboarding, label = "stage") { onboarding ->
                    if (onboarding) {
                        BackHandler {
                            finishOnboarding()
                            showOnboarding = false
                        }
                        AdbOnboardingScreen(
                            discovery = discovery,
                            connection = connection,
                            onOpenDevSettings = { openDeveloperOptions() },
                            onShowPairingNotification = { vm.showPairingNotification() },
                            onSubmitCode = { vm.pair(it) },
                            onSkip = {
                                finishOnboarding()
                                showOnboarding = false
                            },
                            pairingBusy = pairing.busy,
                            pairingMessage = pairing.message,
                            onContinue = {
                                finishOnboarding()
                                showOnboarding = false
                            }
                        )
                    } else {
                        SettingsBlurDrawer(
                            visible = showSettings,
                            config = config,
                            onThemeChange = { theme -> vm.updateConfig { it.copy(theme = theme) } },
                            onNotificationPanelToggle = { on ->
                                vm.updateConfig { it.copy(theme = it.theme.copy(notificationPanel = on)) }
                                vm.refreshNotificationIfActive()
                            },
                            onClose = { showSettings = false }
                        ) {
                            MainDashboardScreen(
                                config = config,
                                runtime = runtime,
                                adbReady = adbReady,
                                onEnable = {
                                    if (!adbReady) {
                                        vm.startDiscovery()
                                        showOnboarding = true
                                    } else {
                                        startEngine(config)
                                    }
                                },
                                onDisable = { LatencyGuardService.disable(this@MainActivity) },
                                onConfigure = { showConfigure = true },
                                onSettings = { showSettings = true },
                                onAdbSetup = {
                                    vm.startDiscovery()
                                    showOnboarding = true
                                },
                                message = message,
                                pendingChanges = pendingChanges,
                                onApplyChanges = { LatencyGuardService.reapply(this@MainActivity) }
                            )
                        }
                    }
                }

                if (showConfigure) {
                    ConfigureSheet(
                        config = config,
                        apps = apps,
                        diagnostics = diagnostics,
                        onConfigChange = { vm.setConfig(it) },
                        onDismiss = {
                            showConfigure = false
                            vm.refreshNotificationIfActive()
                        }
                    )
                }

                if (showPermissions && !permissionStatus.allGranted) {
                    PermissionsDialog(
                        status = permissionStatus,
                        onRequestRuntime = { requestRuntimePermissions() },
                        onOpenUsageAccess = { PermissionHelper.safeStart(this@MainActivity, PermissionHelper.usageAccessIntent()) },
                        onOpenOverlay = { PermissionHelper.safeStart(this@MainActivity, PermissionHelper.overlayIntent(this@MainActivity)) },
                        onContinue = { showPermissions = false }
                    )
                }
            }
        }
    }
}
