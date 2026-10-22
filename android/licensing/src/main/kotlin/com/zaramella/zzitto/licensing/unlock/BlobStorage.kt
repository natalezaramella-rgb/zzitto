package com.zaramella.zzitto.licensing.unlock

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException

/**
 * Archivio di un singolo blob binario. Spec §8.1. Aggiunto il 22/10/2026 alle 11:28.
 */
interface BlobStorage {
    /** @return il contenuto, oppure null se non esiste. */
    fun read(): ByteArray?

    fun write(bytes: ByteArray)
}

/**
 * Blob su file con scrittura atomica ([AtomicFile]): un'interruzione a metà non lascia un file corrotto.
 * Il file va in `noBackupFilesDir`, escluso dal backup automatico Android: lo sblocco non deve
 * viaggiare su un altro dispositivo (lì la chiave del Keystore non esiste); dopo una reinstallazione
 * si ripristina dalla cronologia acquisti di Google Play (Spec §8.1).
 */
class FileBlobStorage(file: File) : BlobStorage {

    private val atomicFile = AtomicFile(file)

    override fun read(): ByteArray? =
        try {
            atomicFile.readFully()
        } catch (e: FileNotFoundException) {
            null
        }

    override fun write(bytes: ByteArray) {
        atomicFile.baseFile.parentFile?.mkdirs()
        val out = atomicFile.startWrite()
        try {
            out.write(bytes)
            atomicFile.finishWrite(out)
        } catch (t: Throwable) {
            atomicFile.failWrite(out)
            throw t
        }
    }
}
