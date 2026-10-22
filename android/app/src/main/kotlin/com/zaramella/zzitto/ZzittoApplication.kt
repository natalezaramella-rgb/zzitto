package com.zaramella.zzitto

import android.app.Application
import com.zaramella.zzitto.core.licensing.LicenseGate
import com.zaramella.zzitto.data.HistoryRepository
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application con Hilt (Spec §3). Aggiunto il 22/10/2026 alle 11:59.
 */
@HiltAndroidApp
class ZzittoApplication : Application() {

    // Lazy: il database (e il Keystore) si aprono fuori dal thread principale.
    @Inject lateinit var historyRepository: Lazy<HistoryRepository>

    @Inject lateinit var licenseGate: Lazy<LicenseGate>

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Spec §5.6: a ogni avvio si applica la conservazione configurata della cronologia.
        appScope.launch { runCatching { historyRepository.get().purgeWithCurrentSettings() } }
        // Spec §8.1: verifica dell'acquisto a ogni avvio (durante la prova). refresh() non lancia eccezioni.
        appScope.launch { licenseGate.get().refresh() }
    }
}
