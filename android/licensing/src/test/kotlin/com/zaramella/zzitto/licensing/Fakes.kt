package com.zaramella.zzitto.licensing

import android.app.Activity
import com.zaramella.zzitto.licensing.purchase.Ownership
import com.zaramella.zzitto.licensing.purchase.PurchaseFlowResult
import com.zaramella.zzitto.licensing.purchase.PurchaseVerifier
import com.zaramella.zzitto.licensing.trial.TrialRecord
import com.zaramella.zzitto.licensing.trial.TrialStore
import com.zaramella.zzitto.licensing.unlock.BlobStorage
import com.zaramella.zzitto.licensing.unlock.LocalCipher
import com.zaramella.zzitto.licensing.unlock.UnlockStore
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Doppi di test del modulo :licensing. Spec §12. Aggiunto il 22/10/2026 alle 11:28.

const val DAY = 86_400_000L

/** 22/10/2026 10:00 UTC. */
const val T0 = 1_792_663_200_000L

class FakeClock(var now: Long = T0) : WallClock {
    override fun nowMillis(): Long = now
}

class FakeTrialStore(
    var record: TrialRecord? = null,
    var available: Boolean = true,
) : TrialStore {
    var writes = 0

    override suspend fun read(): TrialRecord? {
        if (!available) throw IllegalStateException("Supporto non disponibile")
        return record
    }

    override suspend fun write(record: TrialRecord) {
        if (!available) throw IllegalStateException("Supporto non disponibile")
        writes++
        this.record = record
    }
}

class FakeUnlockStore(var unlocked: Boolean = false, var failSave: Boolean = false) : UnlockStore {
    var saves = 0
    override fun isUnlocked(): Boolean = unlocked
    override fun saveUnlocked(): Boolean {
        saves++
        if (failSave) return false
        unlocked = true
        return true
    }
}

class FakeVerifier : PurchaseVerifier {
    val updates = MutableSharedFlow<Ownership>(extraBufferCapacity = 8)
    override val purchaseUpdates = updates

    /** Risposta di [queryOwnership]; null = non risponde mai (simula una rete bloccata). */
    var ownership: Ownership? = Ownership.NotOwned
    var throwOnQuery = false
    var flowResult: PurchaseFlowResult = PurchaseFlowResult.Cancelled
    var queries = 0
    var launches = 0
    var releases = 0

    override suspend fun queryOwnership(): Ownership {
        queries++
        if (throwOnQuery) throw IllegalStateException("Errore simulato")
        return ownership ?: awaitCancellation()
    }

    override suspend fun launchPurchaseFlow(activity: Activity): PurchaseFlowResult {
        launches++
        return flowResult
    }

    override fun release() {
        releases++
    }
}

class InMemoryBlob(var bytes: ByteArray? = null) : BlobStorage {
    override fun read(): ByteArray? = bytes?.copyOf()
    override fun write(bytes: ByteArray) {
        this.bytes = bytes.copyOf()
    }
}

/** AES-GCM software con chiave in memoria: stesso formato di KeystoreCipher (IV || testo cifrato). */
class SoftwareGcmCipher(private val key: SecretKey = newKey()) : LocalCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
        return cipher.doFinal(blob, 12, blob.size - 12)
    }

    companion object {
        fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}
