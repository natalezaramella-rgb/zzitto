package com.zaramella.zzitto.licensing.di

import android.content.Context
import com.zaramella.zzitto.core.licensing.LicenseGate
import com.zaramella.zzitto.licensing.AccountTrial
import com.zaramella.zzitto.licensing.LicensingConfig
import com.zaramella.zzitto.licensing.LicensingIo
import com.zaramella.zzitto.licensing.LicensingScope
import com.zaramella.zzitto.licensing.LocalTrial
import com.zaramella.zzitto.licensing.PlayLicenseGate
import com.zaramella.zzitto.licensing.SystemWallClock
import com.zaramella.zzitto.licensing.WallClock
import com.zaramella.zzitto.licensing.purchase.PlayBillingPurchaseVerifier
import com.zaramella.zzitto.licensing.purchase.PurchaseVerifier
import com.zaramella.zzitto.licensing.trial.BlockStoreTrialStore
import com.zaramella.zzitto.licensing.trial.SharedPrefsTrialStore
import com.zaramella.zzitto.licensing.trial.TrialStore
import com.zaramella.zzitto.licensing.unlock.EncryptedUnlockStore
import com.zaramella.zzitto.licensing.unlock.FileBlobStorage
import com.zaramella.zzitto.licensing.unlock.KeystoreCipher
import com.zaramella.zzitto.licensing.unlock.LocalCipher
import com.zaramella.zzitto.licensing.unlock.UnlockStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Singleton

/**
 * Collegamenti Hilt del modulo :licensing. Spec §4.2, §8.1. Aggiunto il 22/10/2026 alle 11:28.
 * [LicenseGate] è un singleton: l'app (agente B) lo inietta e chiama `refresh()` a ogni avvio.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LicensingBindingsModule {

    @Binds
    @Singleton
    abstract fun bindLicenseGate(impl: PlayLicenseGate): LicenseGate

    @Binds
    abstract fun bindPurchaseVerifier(impl: PlayBillingPurchaseVerifier): PurchaseVerifier

    @Binds
    @LocalTrial
    abstract fun bindLocalTrialStore(impl: SharedPrefsTrialStore): TrialStore

    @Binds
    @AccountTrial
    abstract fun bindAccountTrialStore(impl: BlockStoreTrialStore): TrialStore

    @Binds
    abstract fun bindLocalCipher(impl: KeystoreCipher): LocalCipher
}

@Module
@InstallIn(SingletonComponent::class)
object LicensingProvidersModule {

    private const val UNLOCK_FILE = "zzitto_unlock.bin"

    @Provides
    @Singleton
    fun provideWallClock(): WallClock = SystemWallClock

    @Provides
    @Singleton
    @LicensingScope
    fun provideLicensingScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @LicensingIo
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /** Spec §8.1: sblocco cifrato in `noBackupFilesDir` con chiave nell'Android Keystore. */
    @Provides
    @Singleton
    fun provideUnlockStore(
        @ApplicationContext context: Context,
        cipher: LocalCipher,
    ): UnlockStore = EncryptedUnlockStore(
        storage = FileBlobStorage(File(context.noBackupFilesDir, UNLOCK_FILE)),
        cipher = cipher,
        payload = EncryptedUnlockStore.payloadFor(context.packageName, LicensingConfig.PRODUCT_ID),
    )
}
