package com.zaramella.zzitto.di

import android.content.Context
import com.zaramella.zzitto.audio.AndroidMicrophoneDirectory
import com.zaramella.zzitto.audio.MicrophoneDirectory
import com.zaramella.zzitto.data.HistoryRepository
import com.zaramella.zzitto.data.PersonalizationRepository
import com.zaramella.zzitto.data.crypto.AesGcmKeyWrapper
import com.zaramella.zzitto.data.crypto.AndroidKeystoreKeys
import com.zaramella.zzitto.data.crypto.DatabasePassphraseStore
import com.zaramella.zzitto.data.db.ZzDatabase
import com.zaramella.zzitto.dictation.DictationHistorySink
import com.zaramella.zzitto.dictation.PersonalizationSource
import com.zaramella.zzitto.dictation.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Collegamenti Hilt del modulo :app: database cifrato e repository (Spec §4.2, §10).
 * Aggiunto il 22/10/2026 alle 11:59.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppDataModule {

    private const val KEY_ALIAS = "zzitto_database_key"

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ZzDatabase {
        val store = DatabasePassphraseStore(
            file = File(context.noBackupFilesDir, "zzitto_db.key"),
            wrapper = AesGcmKeyWrapper { AndroidKeystoreKeys.getOrCreate(KEY_ALIAS) },
        )
        return ZzDatabase.build(context, store)
    }

    @Provides
    @Singleton
    fun provideHistoryRepository(db: ZzDatabase, settings: SettingsRepository): HistoryRepository =
        HistoryRepository(db.historyDao(), settings.settings)

    @Provides
    @Singleton
    fun providePersonalizationRepository(db: ZzDatabase): PersonalizationRepository =
        PersonalizationRepository(db.dictionaryDao(), db.shortcutDao())

    /** Spec §5.7: microfoni disponibili per la scelta nelle impostazioni. Aggiunto il 22/10/2026 alle 17:44. */
    @Provides
    @Singleton
    fun provideMicrophoneDirectory(@ApplicationContext context: Context): MicrophoneDirectory =
        AndroidMicrophoneDirectory(context)
}

/** Implementazioni delle interfacce del modulo :dictation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {

    @Binds
    abstract fun bindPersonalizationSource(repository: PersonalizationRepository): PersonalizationSource

    @Binds
    abstract fun bindHistorySink(repository: HistoryRepository): DictationHistorySink
}
