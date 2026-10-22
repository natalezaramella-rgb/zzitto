package com.zaramella.zzitto.engine.di

import android.content.Context
import com.zaramella.zzitto.engine.AndroidModelAssetSource
import com.zaramella.zzitto.engine.EngineNative
import com.zaramella.zzitto.engine.JniEngineNative
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
        val versionCode = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        return ModelInstaller(
            source = AndroidModelAssetSource(context.assets),
            targetDir = File(context.noBackupFilesDir, "models"),
            appVersionCode = versionCode,
        )
    }

    @Provides
    @Singleton
    fun provideZzEngine(native: EngineNative, installer: ModelInstaller): ZzEngine =
        ZzEngine(native = native, configProvider = installer)
}
