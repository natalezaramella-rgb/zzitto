package com.zaramella.zzitto.engine.di

import android.content.Context
import com.zaramella.zzitto.engine.AndroidModelAssetSource
import com.zaramella.zzitto.engine.BuildConfig
import com.zaramella.zzitto.engine.EngineNative
import com.zaramella.zzitto.engine.JniEngineNative
import com.zaramella.zzitto.engine.ModelManifest
import com.zaramella.zzitto.engine.ModelInstaller
import com.zaramella.zzitto.engine.ZzEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Collegamenti Hilt del modulo :engine (Spec §4.2, §4.3, §7).
 * Aggiunto il 22/10/2026 alle 11:30.
 */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {

    @Provides
    @Singleton
    fun provideEngineNative(): EngineNative = JniEngineNative()

    @Provides
    @Singleton
    fun provideModelInstaller(@ApplicationContext context: Context): ModelInstaller {
        // Spec §7: la copia si rifà quando cambia la versione dei modelli; finché lo script
        // dell'agente E non la imposta si usa la versione dell'app.
        val installKey = if (ModelManifest.hasModelsVersion) {
            ModelManifest.MODELS_VERSION
        } else {
            "app-" + context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }
        return ModelInstaller(
            source = AndroidModelAssetSource(context.assets),
            targetDir = File(context.noBackupFilesDir, "models"),
            installKey = installKey,
            // Con il motore finto (debug) gli hash possono mancare; in release sono obbligatori.
            requireHashes = !BuildConfig.ENGINE_MOCK,
            // Debug con mock: si avvia anche senza i modelli negli asset (integrazione 22/10/2026 12:37).
            allowMissingModels = BuildConfig.ENGINE_MOCK,
        )
    }

    @Provides
    @Singleton
    fun provideZzEngine(native: EngineNative, installer: ModelInstaller): ZzEngine =
        ZzEngine(native = native, configProvider = installer)
}
