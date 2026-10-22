package com.zaramella.zzitto.ui

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zaramella.zzitto.R
import com.zaramella.zzitto.audio.Microphone
import com.zaramella.zzitto.audio.MicrophoneCatalog
import com.zaramella.zzitto.audio.MicrophoneLabels
import com.zaramella.zzitto.data.DictionaryWord
import com.zaramella.zzitto.data.HistoryEntry
import com.zaramella.zzitto.data.SaveResult
import com.zaramella.zzitto.data.ShortcutItem
import com.zaramella.zzitto.dictation.settings.DictationSettings
import com.zaramella.zzitto.engine.CorrectionMode
import com.zaramella.zzitto.engine.TranslateTarget
import com.zaramella.zzitto.engine.WhisperModel
import java.util.Date
import java.util.Locale

/**
 * Impostazioni, dizionario, scorciatoie e cronologia (Spec §5.5, §5.6, §5.7, §5.11).
 * Aggiunto il 22/10/2026 alle 11:59.
 */

fun languageLabel(code: String): String =
    Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

@Composable
fun modeLabel(mode: CorrectionMode): String = stringResource(
    when (mode) {
        CorrectionMode.FAITHFUL -> R.string.mode_faithful
        CorrectionMode.CLEAN -> R.string.mode_clean
        CorrectionMode.FORMAL -> R.string.mode_formal
    },
)

@Composable
fun translateLabel(target: TranslateTarget): String = stringResource(
    when (target) {
        TranslateTarget.NONE -> R.string.translate_none
        TranslateTarget.EN -> R.string.translate_en
        TranslateTarget.ES -> R.string.translate_es
    },
)

/** Spec §5.7: testi dei nomi dei microfoni dalle risorse, nella lingua del sistema. Aggiunto il 22/10/2026 alle 17:44. */
@Composable
fun microphoneLabels(): MicrophoneLabels = MicrophoneLabels(
    builtin = stringResource(R.string.mic_builtin),
    wiredHeadset = stringResource(R.string.mic_wired),
    wiredHeadsetNamed = stringResource(R.string.mic_wired_named),
    usb = stringResource(R.string.mic_usb),
    usbNamed = stringResource(R.string.mic_usb_named),
    bluetooth = stringResource(R.string.mic_bluetooth),
    bluetoothNamed = stringResource(R.string.mic_bluetooth_named),
)

/**
 * Spec §5.7: scelta del microfono fra "Predefinito di sistema" e quelli collegati. Se il microfono
 * salvato non è collegato nessuna voce è selezionata e una nota avvisa che si usa il predefinito.
 * Aggiunto il 22/10/2026 alle 17:44.
 */
@Composable
fun MicrophoneSelector(
    selectedKey: String?,
    microphones: List<Microphone>,
    onSelect: (String?) -> Unit,
) {
    val names = MicrophoneCatalog.displayNames(microphones, microphoneLabels())
    val resolved = MicrophoneCatalog.choose(selectedKey, microphones)
    val defaultLabel = stringResource(R.string.mic_system_default)
    RadioGroup(
        options = listOf<String?>(null) + microphones.map { it.key },
        selected = if (selectedKey == null) null else resolved?.key ?: selectedKey,
        label = { key -> if (key == null) defaultLabel else names[key] ?: key },
        onSelect = onSelect,
    )
    if (selectedKey != null && resolved == null) {
        Text(
            stringResource(R.string.mic_not_connected),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("micNotConnected"),
        )
    }
}

/** Spec §5.7: lingua, modalità, silenzio, durata massima, cronologia, dizionario, vibrazione/suono, microfono. */
@Composable
fun SettingsScreen(
    settings: DictationSettings,
    microphones: List<Microphone>,
    onChange: ((DictationSettings) -> DictationSettings) -> Unit,
    onNavigate: (Screen) -> Unit,
    onRepeatTour: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onBack: () -> Unit,
) {
    var showDisclosure by rememberSaveable { mutableStateOf(false) }
    if (showDisclosure) {
        AccessibilityDisclosureDialog(
            onAccept = {
                showDisclosure = false
                onOpenAccessibilitySettings()
            },
            onDecline = { showDisclosure = false },
        )
    }
    ZzScaffold(stringResource(R.string.screen_settings), stringResource(R.string.help_settings), onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_language))
            RadioGroup(
                options = DictationSettings.SUPPORTED_LANGUAGES,
                selected = settings.language,
                label = { languageLabel(it) },
                onSelect = { code -> onChange { it.copy(language = code) } },
            )

            SectionTitle(stringResource(R.string.settings_mode))
            RadioGroup(
                options = CorrectionMode.entries,
                selected = settings.mode,
                label = { modeLabel(it) },
                onSelect = { mode -> onChange { it.copy(mode = mode) } },
            )
            // Spec §5.7, §11 (22/10/2026 22:09): Whisper small (veloce, predefinito) o medium (massima precisione).
            SwitchRow(
                label = stringResource(R.string.settings_max_precision),
                description = stringResource(R.string.settings_max_precision_desc),
                checked = settings.whisperModel == WhisperModel.MEDIUM,
                onChange = { v -> onChange { it.copy(whisperModel = if (v) WhisperModel.MEDIUM else WhisperModel.SMALL) } },
            )

            SectionTitle(stringResource(R.string.settings_translate))
            RadioGroup(
                options = TranslateTarget.entries,
                selected = settings.translate,
                label = { translateLabel(it) },
                onSelect = { t -> onChange { it.copy(translate = t) } },
            )

            SectionTitle(stringResource(R.string.settings_listening))
            SwitchRow(
                label = stringResource(R.string.settings_auto_stop),
                checked = settings.autoStopOnSilence,
                onChange = { v -> onChange { it.copy(autoStopOnSilence = v) } },
            )
            if (settings.autoStopOnSilence) {
                IntSliderRow(
                    label = stringResource(R.string.settings_silence),
                    value = settings.silenceTimeoutMs,
                    range = DictationSettings.MIN_SILENCE_MS..DictationSettings.MAX_SILENCE_MS,
                    step = 500,
                    format = { ms -> String.format(Locale.getDefault(), "%.1f s", ms / 1000f) },
                    onChange = { v -> onChange { it.copy(silenceTimeoutMs = v) } },
                )
            }
            IntSliderRow(
                label = stringResource(R.string.settings_max_duration),
                value = (settings.maxDurationMs / 60_000L).toInt().coerceAtLeast(1),
                range = 1..(DictationSettings.MAX_MAX_DURATION_MS / 60_000L).toInt(),
                step = 1,
                format = { min -> "$min min" },
                onChange = { v -> onChange { it.copy(maxDurationMs = v * 60_000L) } },
            )
            SwitchRow(
                label = stringResource(R.string.settings_voice_commands),
                description = stringResource(R.string.settings_voice_commands_desc),
                checked = settings.voiceCommandsEnabled,
                onChange = { v -> onChange { it.copy(voiceCommandsEnabled = v) } },
            )
            SwitchRow(
                label = stringResource(R.string.settings_vibrate),
                checked = settings.vibrateOnStart,
                onChange = { v -> onChange { it.copy(vibrateOnStart = v) } },
            )
            SwitchRow(
                label = stringResource(R.string.settings_sound),
                checked = settings.soundOnStart,
                onChange = { v -> onChange { it.copy(soundOnStart = v) } },
            )

            // Spec §5.7: microfono da usare. Aggiunto il 22/10/2026 alle 17:44.
            SectionTitle(stringResource(R.string.settings_microphone))
            MicrophoneSelector(
                selectedKey = settings.microphoneKey,
                microphones = microphones,
                onSelect = { key -> onChange { it.copy(microphoneKey = key) } },
            )

            SectionTitle(stringResource(R.string.settings_memory))
            IntSliderRow(
                label = stringResource(R.string.settings_unload),
                value = settings.unloadAfterMinutes,
                range = DictationSettings.MIN_UNLOAD_MINUTES..DictationSettings.MAX_UNLOAD_MINUTES,
                step = 1,
                format = { min -> "$min min" },
                onChange = { v -> onChange { it.copy(unloadAfterMinutes = v) } },
            )

            SectionTitle(stringResource(R.string.settings_data))
            NavButton(stringResource(R.string.screen_dictionary)) { onNavigate(Screen.DICTIONARY) }
            NavButton(stringResource(R.string.screen_shortcuts)) { onNavigate(Screen.SHORTCUTS) }
            NavButton(stringResource(R.string.screen_history)) { onNavigate(Screen.HISTORY) }

            SectionTitle(stringResource(R.string.settings_info))
            // Spec §5.1-bis: l'informativa sull'accessibilità si può riaprire in ogni momento.
            NavButton(stringResource(R.string.settings_accessibility_disclosure)) { showDisclosure = true }
            // Spec §10: informativa privacy linkata nell'app.
            NavButton(stringResource(R.string.settings_privacy_policy)) { onOpenPrivacyPolicy() }
            NavButton(stringResource(R.string.screen_tour)) { onRepeatTour() }
            NavButton(stringResource(R.string.screen_purchase)) { onNavigate(Screen.PURCHASE) }
            NavButton(stringResource(R.string.screen_licenses)) { onNavigate(Screen.LICENSES) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun saveResultText(result: SaveResult?): String? = when (result) {
    null -> null
    SaveResult.SAVED -> stringResource(R.string.save_ok)
    SaveResult.EMPTY -> stringResource(R.string.save_empty)
    SaveResult.TOO_LONG -> stringResource(R.string.save_too_long)
    SaveResult.DUPLICATE -> stringResource(R.string.save_duplicate)
}

/** Spec §5.5: parole e nomi propri passati a Whisper e al modello di correzione. */
@Composable
fun DictionaryScreen(
    words: List<DictionaryWord>,
    lastResult: SaveResult?,
    onAdd: (String) -> Unit,
    onRemove: (Long) -> Unit,
    onBack: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    ZzScaffold(stringResource(R.string.screen_dictionary), stringResource(R.string.help_dictionary), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text(stringResource(R.string.dictionary_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("wordInput"),
                    )
                    TextButton(
                        onClick = {
                            onAdd(input)
                            input = ""
                        },
                        modifier = Modifier.testTag("wordAdd"),
                    ) { Text(stringResource(R.string.action_add)) }
                }
                saveResultText(lastResult)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (words.isEmpty()) {
                    Text(stringResource(R.string.dictionary_empty), modifier = Modifier.padding(top = 16.dp))
                }
            }
            items(words, key = { it.id }) { word ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(word.word, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onRemove(word.id) }) { Text(stringResource(R.string.action_delete)) }
                }
                HorizontalDivider()
            }
        }
    }
}

/** Spec §5.11: parola chiave → testo, inserito senza correzione. */
@Composable
fun ShortcutsScreen(
    shortcuts: List<ShortcutItem>,
    lastResult: SaveResult?,
    onSave: (String, String) -> Unit,
    onRemove: (Long) -> Unit,
    onBack: () -> Unit,
) {
    var keyword by rememberSaveable { mutableStateOf("") }
    var expansion by rememberSaveable { mutableStateOf("") }
    ZzScaffold(stringResource(R.string.screen_shortcuts), stringResource(R.string.help_shortcuts), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        label = { Text(stringResource(R.string.shortcuts_keyword)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("shortcutKeyword"),
                    )
                    OutlinedTextField(
                        value = expansion,
                        onValueChange = { expansion = it },
                        label = { Text(stringResource(R.string.shortcuts_expansion)) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("shortcutExpansion"),
                    )
                    Button(
                        onClick = {
                            onSave(keyword, expansion)
                            keyword = ""
                            expansion = ""
                        },
                        modifier = Modifier.align(Alignment.End).padding(top = 8.dp).testTag("shortcutSave"),
                    ) { Text(stringResource(R.string.action_save)) }
                    saveResultText(lastResult)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (shortcuts.isEmpty()) {
                        Text(stringResource(R.string.shortcuts_empty), modifier = Modifier.padding(top = 16.dp))
                    }
                }
            }
            items(shortcuts, key = { it.id }) { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.keyword, style = MaterialTheme.typography.titleSmall)
                        Text(item.expansion, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onRemove(item.id) }) { Text(stringResource(R.string.action_delete)) }
                }
                HorizontalDivider()
            }
        }
    }
}

/** Spec §5.6: cronologia cifrata, disattivabile, cancellazione totale in un tap, conservazione configurabile. */
@Composable
fun HistoryScreen(
    settings: DictationSettings,
    entries: List<HistoryEntry>,
    onChange: ((DictationSettings) -> DictationSettings) -> Unit,
    onRetentionChanged: (Int) -> Unit,
    onDelete: (Long) -> Unit,
    onDeleteAll: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    ZzScaffold(stringResource(R.string.screen_history), stringResource(R.string.help_history), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item {
                SwitchRow(
                    label = stringResource(R.string.history_enabled),
                    description = stringResource(R.string.history_enabled_desc),
                    checked = settings.historyEnabled,
                    onChange = { v -> onChange { it.copy(historyEnabled = v) } },
                )
                IntSliderRow(
                    label = stringResource(R.string.history_retention),
                    value = settings.historyRetentionDays,
                    range = DictationSettings.MIN_RETENTION_DAYS..DictationSettings.MAX_RETENTION_DAYS,
                    step = 1,
                    format = { d -> context.resources.getQuantityString(R.plurals.days, d, d) },
                    onChange = { d ->
                        onChange { it.copy(historyRetentionDays = d) }
                        onRetentionChanged(d)
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = { confirmDeleteAll = true },
                        enabled = entries.isNotEmpty(),
                        modifier = Modifier.testTag("historyDeleteAll"),
                    ) { Text(stringResource(R.string.history_delete_all)) }
                }
                if (entries.isEmpty()) {
                    Text(stringResource(R.string.history_empty), modifier = Modifier.padding(top = 16.dp))
                }
            }
            items(entries, key = { it.id }) { entry ->
                val expanded = expandedId == entry.id
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { expandedId = if (expanded) null else entry.id },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        val date = DateFormat.getMediumDateFormat(context).format(Date(entry.createdAt)) + " " +
                            DateFormat.getTimeFormat(context).format(Date(entry.createdAt))
                        Text(date, style = MaterialTheme.typography.labelMedium)
                        Text(entry.finalText, maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                        if (expanded) {
                            Text(
                                stringResource(R.string.history_raw, entry.rawText),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            TextButton(onClick = { onDelete(entry.id) }, modifier = Modifier.align(Alignment.End)) {
                                Text(stringResource(R.string.action_delete))
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.history_delete_all)) },
            text = { Text(stringResource(R.string.history_delete_all_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteAll = false
                        onDeleteAll()
                    },
                    modifier = Modifier.testTag("historyConfirmDelete"),
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
