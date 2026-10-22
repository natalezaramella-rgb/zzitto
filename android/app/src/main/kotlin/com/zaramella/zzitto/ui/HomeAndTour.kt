package com.zaramella.zzitto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zaramella.zzitto.R
import com.zaramella.zzitto.core.licensing.LicenseState
import com.zaramella.zzitto.engine.ModelInstallError
import com.zaramella.zzitto.engine.ModelInstallState

/**
 * Home e tour al primo avvio (Spec §5.1-bis, §5.8). Aggiunto il 22/10/2026 alle 11:59.
 */

/** Stato di sistema riletto a ogni ritorno sull'app (dopo le impostazioni di sistema). */
@Composable
fun rememberSystemStatus(): SystemStatus {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf(SystemStatus.read(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) status = SystemStatus.read(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return status
}

/** Azioni di sistema richieste da home e tour. */
class SetupActions(
    val openAccessibility: () -> Unit,
    val requestMicrophone: () -> Unit,
    val openBatterySettings: () -> Unit,
    val prepareModels: () -> Unit,
)

@Composable
fun licenseText(state: LicenseState): String = when (state) {
    LicenseState.Unknown -> stringResource(R.string.license_unknown)
    is LicenseState.Trial -> stringResource(R.string.license_trial, state.daysLeft)
    LicenseState.TrialExpired -> stringResource(R.string.license_expired)
    LicenseState.Purchased -> stringResource(R.string.license_purchased)
}

@Composable
fun modelsText(state: ModelInstallState, installed: Boolean): String = when {
    installed || state == ModelInstallState.Ready -> stringResource(R.string.models_ready)
    state is ModelInstallState.Installing -> stringResource(R.string.models_installing, (state.fraction * 100).toInt())
    state is ModelInstallState.Failed -> stringResource(
        when (state.error) {
            ModelInstallError.NO_SPACE -> R.string.models_error_space
            ModelInstallError.INTEGRITY -> R.string.models_error_integrity
            ModelInstallError.MISSING_ASSET -> R.string.models_error_missing
            ModelInstallError.IO -> R.string.models_error_io
        },
    )
    else -> stringResource(R.string.models_todo)
}

@Composable
fun HomeScreen(
    status: SystemStatus,
    license: LicenseState,
    modelState: ModelInstallState,
    modelsInstalled: Boolean,
    actions: SetupActions,
    onNavigate: (Screen) -> Unit,
) {
    ZzScaffold(title = stringResource(R.string.app_name), helpText = stringResource(R.string.help_home), onBack = null) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(stringResource(R.string.home_intro), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp))
            var showDisclosure by rememberSaveable { mutableStateOf(false) }
            StatusCard(
                title = stringResource(R.string.status_accessibility),
                ok = status.accessibilityEnabled,
                okText = stringResource(R.string.status_ok),
                todoText = stringResource(R.string.status_accessibility_todo),
                actionLabel = stringResource(R.string.action_enable),
                onAction = { showDisclosure = true },
            )
            if (showDisclosure) {
                AccessibilityDisclosureDialog(
                    onAccept = {
                        showDisclosure = false
                        actions.openAccessibility()
                    },
                    onDecline = { showDisclosure = false },
                )
            }
            StatusCard(
                title = stringResource(R.string.status_microphone),
                ok = status.microphoneGranted,
                okText = stringResource(R.string.status_ok),
                todoText = stringResource(R.string.status_microphone_todo),
                actionLabel = stringResource(R.string.action_grant),
                onAction = actions.requestMicrophone,
            )
            StatusCard(
                title = stringResource(R.string.status_battery),
                ok = status.batteryUnrestricted,
                okText = stringResource(R.string.status_ok),
                todoText = stringResource(R.string.status_battery_todo),
                actionLabel = stringResource(R.string.action_open_settings),
                onAction = actions.openBatterySettings,
            )
            StatusCard(
                title = stringResource(R.string.status_models),
                ok = modelsInstalled,
                okText = stringResource(R.string.models_ready),
                todoText = modelsText(modelState, modelsInstalled),
                actionLabel = if (modelState is ModelInstallState.Installing) null else stringResource(R.string.action_prepare),
                onAction = actions.prepareModels,
            )
            if (modelState is ModelInstallState.Installing) {
                LinearProgressIndicator(progress = { modelState.fraction }, modifier = Modifier.fillMaxWidth())
            }
            StatusCard(
                title = stringResource(R.string.status_license),
                ok = license is LicenseState.Trial || license == LicenseState.Purchased,
                okText = licenseText(license),
                todoText = licenseText(license),
                actionLabel = stringResource(R.string.action_unlock),
                onAction = { onNavigate(Screen.PURCHASE) },
            )

            SectionTitle(stringResource(R.string.home_try_title))
            TryDictationField()

            SectionTitle(stringResource(R.string.home_more))
            NavButton(stringResource(R.string.screen_settings)) { onNavigate(Screen.SETTINGS) }
            NavButton(stringResource(R.string.screen_dictionary)) { onNavigate(Screen.DICTIONARY) }
            NavButton(stringResource(R.string.screen_shortcuts)) { onNavigate(Screen.SHORTCUTS) }
            NavButton(stringResource(R.string.screen_history)) { onNavigate(Screen.HISTORY) }
            NavButton(stringResource(R.string.screen_purchase)) { onNavigate(Screen.PURCHASE) }
            NavButton(stringResource(R.string.screen_tour)) { onNavigate(Screen.TOUR) }
            NavButton(stringResource(R.string.screen_licenses)) { onNavigate(Screen.LICENSES) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun NavButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(label) }
}

/** Campo per provare la dettatura dentro l'app (Spec §5.8: prima dettatura). Non salvato. */
@Composable
fun TryDictationField() {
    var text by remember { mutableStateOf("") }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().testTag("tryField"),
        label = { Text(stringResource(R.string.home_try_hint)) },
        minLines = 3,
    )
}

enum class TourStep { WELCOME, ACCESSIBILITY, MICROPHONE, BATTERY, MODELS, FIRST_DICTATION }

/**
 * Tour guidato (Spec §5.8, §5.1-bis): privacy, informativa ben visibile e attivazione del servizio
 * di accessibilità, permesso del microfono, esclusione dall'ottimizzazione batteria Samsung,
 * preparazione dei modelli, prima dettatura. Ripetibile dalle impostazioni.
 */
@Composable
fun TourScreen(
    status: SystemStatus,
    modelState: ModelInstallState,
    modelsInstalled: Boolean,
    actions: SetupActions,
    onFinish: () -> Unit,
) {
    var index by rememberSaveable { mutableStateOf(0) }
    val steps = TourStep.entries
    val step = steps[index]
    ZzScaffold(
        title = stringResource(R.string.tour_title, index + 1, steps.size),
        helpText = stringResource(R.string.help_tour),
        onBack = null,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                TourStepContent(step, status, modelState, modelsInstalled, actions)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (index > 0) {
                    TextButton(onClick = { index-- }, modifier = Modifier.testTag("tourBack")) { Text(stringResource(R.string.action_previous)) }
                } else {
                    TextButton(onClick = onFinish, modifier = Modifier.testTag("tourSkip")) { Text(stringResource(R.string.action_skip)) }
                }
                if (index < steps.lastIndex) {
                    Button(onClick = { index++ }, modifier = Modifier.testTag("tourNext")) { Text(stringResource(R.string.action_next)) }
                } else {
                    Button(onClick = onFinish, modifier = Modifier.testTag("tourFinish")) { Text(stringResource(R.string.action_finish)) }
                }
            }
        }
    }
}

@Composable
private fun TourStepContent(
    step: TourStep,
    status: SystemStatus,
    modelState: ModelInstallState,
    modelsInstalled: Boolean,
    actions: SetupActions,
) {
    val titleStyle = MaterialTheme.typography.headlineSmall
    val bodyModifier = Modifier.padding(vertical = 8.dp)
    when (step) {
        TourStep.WELCOME -> {
            Text(stringResource(R.string.tour_welcome_title), style = titleStyle, modifier = Modifier.testTag("tourStepTitle"))
            Text(stringResource(R.string.tour_welcome_body), modifier = bodyModifier)
        }
        TourStep.ACCESSIBILITY -> {
            Text(stringResource(R.string.tour_accessibility_title), style = titleStyle, modifier = Modifier.testTag("tourStepTitle"))
            if (status.accessibilityEnabled) {
                StatusCard(
                    title = stringResource(R.string.status_accessibility),
                    ok = true,
                    okText = stringResource(R.string.status_ok),
                    todoText = "",
                    actionLabel = null,
                    onAction = null,
                )
            } else {
                // Informativa ben visibile con consenso esplicito (Spec §5.1-bis, docs/store/google-play-accessibilita.md).
                var declined by rememberSaveable { mutableStateOf(false) }
                if (declined) {
                    Text(stringResource(R.string.disclosure_declined), modifier = bodyModifier)
                    TextButton(onClick = { declined = false }, modifier = Modifier.testTag("disclosureReopen")) {
                        Text(stringResource(R.string.disclosure_reopen))
                    }
                } else {
                    AccessibilityDisclosure(
                        onAccept = actions.openAccessibility,
                        onDecline = { declined = true },
                    )
                }
            }
        }
        TourStep.MICROPHONE -> {
            Text(stringResource(R.string.tour_microphone_title), style = titleStyle, modifier = Modifier.testTag("tourStepTitle"))
            Text(stringResource(R.string.tour_microphone_body), modifier = bodyModifier)
            StatusCard(
                title = stringResource(R.string.status_microphone),
                ok = status.microphoneGranted,
                okText = stringResource(R.string.status_ok),
                todoText = stringResource(R.string.status_microphone_todo),
                actionLabel = stringResource(R.string.action_grant),
                onAction = actions.requestMicrophone,
            )
        }
        TourStep.BATTERY -> {
            Text(stringResource(R.string.tour_battery_title), style = titleStyle, modifier = Modifier.testTag("tourStepTitle"))
            Text(stringResource(R.string.tour_battery_body), modifier = bodyModifier)
            StatusCard(
                title = stringResource(R.string.status_battery),
                ok = status.batteryUnrestricted,
                okText = stringResource(R.string.status_ok),
                todoText = stringResource(R.string.status_battery_todo),
                actionLabel = stringResource(R.string.action_open_settings),
                onAction = actions.openBatterySettings,
            )
        }
        TourStep.MODELS -> {
            Text(stringResource(R.string.tour_models_title), style = titleStyle, modifier = Modifier.testTag("tourStepTitle"))
            Text(stringResource(R.string.tour_models_body), modifier = bodyModifier)
            StatusCard(
                title = stringResource(R.string.status_models),
                ok = modelsInstalled,
                okText = stringResource(R.string.models_ready),
                todoText = modelsText(modelState, modelsInstalled),
                actionLabel = if (modelState is ModelInstallState.Installing) null else stringResource(R.string.action_prepare),
                onAction = actions.prepareModels,
            )
            if (modelState is ModelInstallState.Installing) {
                LinearProgressIndicator(progress = { modelState.fraction }, modifier = Modifier.fillMaxWidth())
            }
        }
        TourStep.FIRST_DICTATION -> {
            Text(stringResource(R.string.tour_first_title), style = titleStyle, modifier = Modifier.testTag("tourStepTitle"))
            Text(stringResource(R.string.tour_first_body), modifier = bodyModifier)
            TryDictationField()
        }
    }
}

/**
 * Informativa sull'uso del servizio di accessibilità, con i testi di
 * docs/store/google-play-accessibilita.md (agente E). "Accetto e attivo" apre le impostazioni di
 * accessibilità; "Non ora" chiude senza aprire nulla (Spec §5.1-bis).
 */
@Composable
fun AccessibilityDisclosure(onAccept: () -> Unit, onDecline: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("accessibilityDisclosure")) {
        Column(Modifier.padding(16.dp)) {
            DisclosureText()
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDecline, modifier = Modifier.testTag("disclosureDecline")) {
                    Text(stringResource(R.string.disclosure_decline))
                }
                Button(onClick = onAccept, modifier = Modifier.testTag("disclosureAccept")) {
                    Text(stringResource(R.string.disclosure_accept))
                }
            }
        }
    }
}

@Composable
fun AccessibilityDisclosureDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.disclosure_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) { DisclosureText(showTitle = false) }
        },
        confirmButton = { Button(onClick = onAccept) { Text(stringResource(R.string.disclosure_accept)) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.disclosure_decline)) } },
    )
}

@Composable
private fun DisclosureText(showTitle: Boolean = true) {
    if (showTitle) {
        Text(stringResource(R.string.disclosure_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
    Text(stringResource(R.string.disclosure_intro), modifier = Modifier.padding(top = 8.dp))
    Text(stringResource(R.string.disclosure_list_title), modifier = Modifier.padding(top = 8.dp))
    Text("• " + stringResource(R.string.disclosure_item_1))
    Text("• " + stringResource(R.string.disclosure_item_2))
    Text("• " + stringResource(R.string.disclosure_item_3))
    Text(stringResource(R.string.disclosure_not), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    Text(stringResource(R.string.disclosure_revoke), modifier = Modifier.padding(top = 8.dp))
}
