package com.zaramella.zzitto.ui

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zaramella.zzitto.core.licensing.LicenseGate
import com.zaramella.zzitto.core.licensing.LicenseState
import com.zaramella.zzitto.core.licensing.PurchaseOutcome
import com.zaramella.zzitto.data.DictionaryWord
import com.zaramella.zzitto.data.HistoryEntry
import com.zaramella.zzitto.data.HistoryRepository
import com.zaramella.zzitto.data.PersonalizationRepository
import com.zaramella.zzitto.data.SaveResult
import com.zaramella.zzitto.data.ShortcutItem
import com.zaramella.zzitto.dictation.settings.DictationSettings
import com.zaramella.zzitto.dictation.settings.SettingsRepository
import com.zaramella.zzitto.engine.ModelInstallException
import com.zaramella.zzitto.engine.ModelInstallState
import com.zaramella.zzitto.engine.ModelInstaller
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel delle schermate (Spec §4.2: MVVM). Aggiunto il 22/10/2026 alle 11:59.
 */

private const val STOP_TIMEOUT_MS = 5_000L

/** Impostazioni e tour (Spec §5.7, §5.8). */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    /** null finché DataStore non ha letto il file (evita di mostrare il tour per un attimo). */
    val settings: StateFlow<DictationSettings?> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun update(transform: (DictationSettings) -> DictationSettings) {
        viewModelScope.launch { repository.update(transform) }
    }
}

/** Preparazione dei modelli al primo avvio (Spec §7). */
@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val installer: ModelInstaller,
) : ViewModel() {

    val state: StateFlow<ModelInstallState> = installer.state

    private val _installed = MutableStateFlow(false)
    val installed: StateFlow<Boolean> = _installed.asStateFlow()

    init {
        viewModelScope.launch { _installed.value = installer.isInstalled() }
    }

    fun prepare() {
        if (state.value is ModelInstallState.Installing) return
        viewModelScope.launch {
            try {
                installer.ensureInstalled()
                _installed.value = true
            } catch (_: ModelInstallException) {
                _installed.value = false // l'errore è in [state]
            }
        }
    }
}

/** Dizionario personale e scorciatoie (Spec §5.5, §5.11). */
@HiltViewModel
class PersonalizationViewModel @Inject constructor(
    private val repository: PersonalizationRepository,
) : ViewModel() {

    val words: StateFlow<List<DictionaryWord>> = repository.words
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val shortcuts: StateFlow<List<ShortcutItem>> = repository.shortcutItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _lastResult = MutableStateFlow<SaveResult?>(null)
    val lastResult: StateFlow<SaveResult?> = _lastResult.asStateFlow()

    fun addWord(word: String) {
        viewModelScope.launch { _lastResult.value = repository.addWord(word) }
    }

    fun removeWord(id: Long) {
        viewModelScope.launch { repository.removeWord(id) }
    }

    fun saveShortcut(keyword: String, expansion: String) {
        viewModelScope.launch { _lastResult.value = repository.saveShortcut(keyword, expansion) }
    }

    fun removeShortcut(id: Long) {
        viewModelScope.launch { repository.removeShortcut(id) }
    }

    fun clearResult() {
        _lastResult.value = null
    }
}

/** Cronologia (Spec §5.6). */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: HistoryRepository,
) : ViewModel() {

    val entries: StateFlow<List<HistoryEntry>> = repository.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun deleteAll() {
        viewModelScope.launch { repository.deleteAll() }
    }

    fun applyRetention(days: Int) {
        viewModelScope.launch { repository.purge(days) }
    }
}

/** Prova e acquisto (Spec §8.1), tramite l'interfaccia LicenseGate dell'agente D. */
@HiltViewModel
class LicenseViewModel @Inject constructor(
    private val gate: LicenseGate,
) : ViewModel() {

    val state: StateFlow<LicenseState> = gate.state

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _outcome = MutableStateFlow<PurchaseOutcome?>(null)
    val outcome: StateFlow<PurchaseOutcome?> = _outcome.asStateFlow()

    init {
        viewModelScope.launch { gate.refresh() }
    }

    fun purchase(activity: Activity) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                _outcome.value = gate.launchPurchase(activity)
            } finally {
                _busy.value = false
            }
        }
    }

    fun restore() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val restored = gate.restorePurchases()
                _outcome.value = if (restored == LicenseState.Purchased) PurchaseOutcome.AlreadyOwned else null
            } finally {
                _busy.value = false
            }
        }
    }
}
