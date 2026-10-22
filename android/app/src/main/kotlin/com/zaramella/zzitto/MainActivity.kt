package com.zaramella.zzitto

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaramella.zzitto.dictation.ZzittoIntents
import com.zaramella.zzitto.ui.DictionaryScreen
import com.zaramella.zzitto.ui.HistoryScreen
import com.zaramella.zzitto.ui.HistoryViewModel
import com.zaramella.zzitto.ui.HomeScreen
import com.zaramella.zzitto.ui.LicenseViewModel
import com.zaramella.zzitto.ui.LicensesScreen
import com.zaramella.zzitto.ui.ModelsViewModel
import com.zaramella.zzitto.ui.PersonalizationViewModel
import com.zaramella.zzitto.ui.PurchaseScreen
import com.zaramella.zzitto.ui.Screen
import com.zaramella.zzitto.ui.SettingsScreen
import com.zaramella.zzitto.ui.SettingsViewModel
import com.zaramella.zzitto.ui.SetupActions
import com.zaramella.zzitto.ui.ShortcutsScreen
import com.zaramella.zzitto.ui.SystemIntents
import com.zaramella.zzitto.ui.TourScreen
import com.zaramella.zzitto.ui.ZzittoTheme
import com.zaramella.zzitto.ui.rememberSystemStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Unica activity: impostazioni, guida, tour, cronologia, licenze e acquisto, aperti solo su
 * richiesta (Spec §2.5). Aggiunto il 22/10/2026 alle 11:59.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Schermata richiesta da un intent (icona flottante: acquisto o impostazioni). */
    private val requestedScreen = MutableStateFlow<Screen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            ZzittoTheme {
                ZzittoNavigation(requestedScreen)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        requestedScreen.value = when (intent?.action) {
            ZzittoIntents.ACTION_OPEN_PURCHASE -> Screen.PURCHASE
            ZzittoIntents.ACTION_OPEN_SETTINGS -> Screen.SETTINGS
            else -> requestedScreen.value
        }
    }
}

private val backStackSaver = Saver<List<Screen>, ArrayList<String>>(
    save = { stack -> ArrayList(stack.map { it.name }) },
    restore = { names -> names.mapNotNull { name -> Screen.entries.firstOrNull { it.name == name } }.ifEmpty { listOf(Screen.HOME) } },
)

@Composable
private fun ZzittoNavigation(requestedScreen: MutableStateFlow<Screen?>) {
    val context = LocalContext.current
    val settingsVm: SettingsViewModel = viewModel()
    val modelsVm: ModelsViewModel = viewModel()
    val licenseVm: LicenseViewModel = viewModel()

    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    val modelState by modelsVm.state.collectAsStateWithLifecycle()
    val modelsInstalled by modelsVm.installed.collectAsStateWithLifecycle()
    val license by licenseVm.state.collectAsStateWithLifecycle()
    val requested by requestedScreen.collectAsStateWithLifecycle()
    val status = rememberSystemStatus()

    var stack by rememberSaveable(stateSaver = backStackSaver) { mutableStateOf(listOf(Screen.HOME)) }
    fun push(screen: Screen) {
        if (stack.last() != screen) stack = stack + screen
    }
    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    // Spec §5.8: tour al primo avvio.
    LaunchedEffect(settings?.tourCompleted) {
        if (settings?.tourCompleted == false && stack.last() != Screen.TOUR) push(Screen.TOUR)
    }
    LaunchedEffect(requested) {
        requested?.let {
            push(it)
            requestedScreen.value = null
        }
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val actions = SetupActions(
        openAccessibility = { safeStart(context, SystemIntents.accessibilitySettings()) },
        requestMicrophone = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        openBatterySettings = {
            if (!safeStart(context, SystemIntents.appDetails(context))) {
                safeStart(context, SystemIntents.batteryOptimizationList())
            }
        },
        prepareModels = { modelsVm.prepare() },
    )

    BackHandler(enabled = stack.size > 1) { pop() }

    val current = settings
    when (stack.last()) {
        Screen.HOME -> HomeScreen(status, license, modelState, modelsInstalled, actions, ::push)
        Screen.TOUR -> TourScreen(status, modelState, modelsInstalled, actions) {
            settingsVm.update { it.copy(tourCompleted = true) }
            pop()
        }
        Screen.SETTINGS -> if (current != null) {
            // Spec §5.7: elenco dei microfoni, osservato solo mentre la schermata è aperta.
            // Aggiunto il 22/10/2026 alle 17:44.
            val microphones by settingsVm.microphones.collectAsStateWithLifecycle()
            SettingsScreen(
                settings = current,
                microphones = microphones,
                onChange = settingsVm::update,
                onNavigate = ::push,
                onRepeatTour = { push(Screen.TOUR) },
                onOpenAccessibilitySettings = actions.openAccessibility,
                onOpenPrivacyPolicy = {
                    val url = context.getString(R.string.privacy_policy_url)
                    safeStart(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                onBack = ::pop,
            )
        }
        Screen.DICTIONARY -> {
            val vm: PersonalizationViewModel = viewModel()
            val words by vm.words.collectAsStateWithLifecycle()
            val result by vm.lastResult.collectAsStateWithLifecycle()
            DictionaryScreen(words, result, vm::addWord, vm::removeWord) {
                vm.clearResult()
                pop()
            }
        }
        Screen.SHORTCUTS -> {
            val vm: PersonalizationViewModel = viewModel()
            val items by vm.shortcuts.collectAsStateWithLifecycle()
            val result by vm.lastResult.collectAsStateWithLifecycle()
            ShortcutsScreen(items, result, vm::saveShortcut, vm::removeShortcut) {
                vm.clearResult()
                pop()
            }
        }
        Screen.HISTORY -> if (current != null) {
            val vm: HistoryViewModel = viewModel()
            val entries by vm.entries.collectAsStateWithLifecycle()
            SecureWindow()
            HistoryScreen(
                settings = current,
                entries = entries,
                onChange = settingsVm::update,
                onRetentionChanged = vm::applyRetention,
                onDelete = vm::delete,
                onDeleteAll = vm::deleteAll,
                onBack = ::pop,
            )
        }
        Screen.LICENSES -> LicensesScreen(onBack = ::pop)
        Screen.PURCHASE -> {
            val busy by licenseVm.busy.collectAsStateWithLifecycle()
            val outcome by licenseVm.outcome.collectAsStateWithLifecycle()
            PurchaseScreen(
                state = license,
                busy = busy,
                outcome = outcome,
                onPurchase = { (context as? android.app.Activity)?.let(licenseVm::purchase) },
                onRestore = licenseVm::restore,
                onBack = ::pop,
            )
        }
    }
}

/** Spec §10: la cronologia contiene testo dettato, niente screenshot né anteprime nelle app recenti. */
@Composable
private fun SecureWindow() {
    val activity = LocalContext.current as? android.app.Activity ?: return
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private fun safeStart(context: android.content.Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}
