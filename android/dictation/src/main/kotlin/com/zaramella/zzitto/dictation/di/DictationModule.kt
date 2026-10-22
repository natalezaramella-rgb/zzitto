package com.zaramella.zzitto.dictation.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.zaramella.zzitto.dictation.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** File DataStore delle impostazioni (Spec §5.7). Una sola istanza per processo. */
private val Context.zzSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "zzitto_settings")

/**
 * Collegamenti Hilt del modulo :dictation. Le implementazioni di [com.zaramella.zzitto.dictation.PersonalizationSource]
 * e [com.zaramella.zzitto.dictation.DictationHistorySink] arrivano dal modulo :app; [com.zaramella.zzitto.core.licensing.LicenseGate]
 * dal modulo :licensing (agente D).
 * Aggiunto il 22/10/2026 alle 11:59.
 */
@Module
@InstallIn(SingletonComponent::class)
object DictationModule {

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository =
        SettingsRepository(context.zzSettingsStore)
}
