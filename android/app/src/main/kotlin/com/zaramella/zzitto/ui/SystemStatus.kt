package com.zaramella.zzitto.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.zaramella.zzitto.dictation.service.ZzittoAccessibilityService

/**
 * Stato dei permessi e delle impostazioni di sistema mostrati dal tour e dalla home (Spec §5.1-bis, §5.8).
 * Aggiunto il 22/10/2026 alle 11:59.
 */
data class SystemStatus(
    val accessibilityEnabled: Boolean,
    val microphoneGranted: Boolean,
    val batteryUnrestricted: Boolean,
) {
    val ready: Boolean
        get() = accessibilityEnabled && microphoneGranted

    companion object {
        fun read(context: Context): SystemStatus = SystemStatus(
            accessibilityEnabled = isAccessibilityServiceEnabled(context),
            microphoneGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
            batteryUnrestricted = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
        )

        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val expected = ComponentName(context, ZzittoAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return EnabledServices.contains(enabled, expected.flattenToString(), expected.flattenToShortString())
        }
    }
}

/** Intent verso le schermate di sistema usate dal tour. */
object SystemIntents {
    /** Impostazioni di accessibilità, dove l'utente attiva il servizio (Spec §5.1-bis). */
    fun accessibilitySettings(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Informazioni sull'app: su Samsung da qui si sceglie Batteria → "Senza restrizioni"
     * (Spec §5.1-bis). Non usiamo ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, che richiede un
     * permesso soggetto alle norme di Google Play.
     */
    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Elenco generale delle ottimizzazioni della batteria, alternativa all'app details. */
    fun batteryOptimizationList(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
