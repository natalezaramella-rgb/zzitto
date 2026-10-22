package com.zaramella.zzitto.engine

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** Copia e verifica dei modelli (Spec §7). Aggiunto il 22/10/2026 alle 11:35. */
class ModelInstallerTest {

    @TempDir
    lateinit var dir: File

    private class MapSource(val files: Map<String, ByteArray>) : ModelAssetSource {
        var opens = 0
        override fun openOrNull(path: String): InputStream? {
            val data = files[path] ?: return null
            opens++
            return ByteArrayInputStream(data)
        }
    }

    private fun sha(vararg parts: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private val whisperData = ByteArray(3_000) { (it % 251).toByte() }
    private val llmPart0 = ByteArray(2_000) { 7 }
    private val llmPart1 = ByteArray(1_500) { 9 }

    private fun models(whisperHash: String = sha(whisperData), llmHash: String = sha(llmPart0, llmPart1)) = listOf(
        ModelFile(ModelManifest.WHISPER_FILE, whisperHash, 3_000),
        ModelFile(ModelManifest.LLM_FILE, llmHash, 3_500),
    )

    private fun source() = MapSource(
        mapOf(
            "models/${ModelManifest.WHISPER_FILE}" to whisperData,
            "models/${ModelManifest.LLM_FILE}.part00" to llmPart0,
            "models/${ModelManifest.LLM_FILE}.part01" to llmPart1,
        ),
    )

    @Test
    fun `copia i modelli, riunisce le parti e passa gli hash al motore`() = runBlocking {
        val installer = ModelInstaller(source(), dir, appVersionCode = 3, models = models(), ioDispatcher = Dispatchers.Unconfined)
        val config = installer.engineConfig()
        assertTrue(File(config.whisperModelPath).readBytes().contentEquals(whisperData))
        assertTrue(File(config.llmModelPath).readBytes().contentEquals(llmPart0 + llmPart1))
        assertEquals(sha(whisperData), config.whisperSha256)
        assertEquals(sha(llmPart0, llmPart1), config.llmSha256)
        assertEquals(ModelInstallState.Ready, installer.state.value)
        assertTrue(installer.isInstalled())
    }

    @Test
    fun `alla seconda chiamata non ricopia`() = runBlocking {
        val src = source()
        val installer = ModelInstaller(src, dir, 3, models(), Dispatchers.Unconfined)
        installer.ensureInstalled()
        val opens = src.opens
        installer.ensureInstalled()
        assertEquals(opens, src.opens)
    }

    @Test
    fun `una nuova versione dell'app ricopia i modelli`() = runBlocking {
        val src = source()
        ModelInstaller(src, dir, 3, models(), Dispatchers.Unconfined).ensureInstalled()
        val installer = ModelInstaller(src, dir, 4, models(), Dispatchers.Unconfined)
        assertFalse(installer.isInstalled())
        installer.ensureInstalled()
        assertTrue(installer.isInstalled())
    }

    @Test
    fun `hash errato blocca l'installazione e non lascia file`() {
        val installer = ModelInstaller(source(), dir, 3, models(whisperHash = "0".repeat(64)), Dispatchers.Unconfined)
        val e = assertThrows<ModelInstallException> { runBlocking { installer.ensureInstalled() } }
        assertEquals(ModelInstallError.INTEGRITY, e.error)
        assertEquals(ModelInstallState.Failed(ModelInstallError.INTEGRITY), installer.state.value)
        assertFalse(File(dir, ModelManifest.WHISPER_FILE).exists())
        assertFalse(File(dir, ModelManifest.WHISPER_FILE + ".tmp").exists())
    }

    @Test
    fun `asset mancante`() {
        val installer = ModelInstaller(MapSource(emptyMap()), dir, 3, models(), Dispatchers.Unconfined)
        val e = assertThrows<ModelInstallException> { runBlocking { installer.ensureInstalled() } }
        assertEquals(ModelInstallError.MISSING_ASSET, e.error)
    }

    @Test
    fun `con hash segnaposto copia ma non chiede la verifica al motore`() = runBlocking {
        val placeholder = ModelManifest.HASH_PLACEHOLDER
        val installer = ModelInstaller(source(), dir, 3, models(placeholder, placeholder), Dispatchers.Unconfined)
        val config = installer.engineConfig()
        assertNull(config.whisperSha256)
        assertNull(config.llmSha256)
    }

    @Test
    fun `file corrotto dopo l'installazione forza una nuova copia`() = runBlocking {
        val installer = ModelInstaller(source(), dir, 3, models(), Dispatchers.Unconfined)
        installer.ensureInstalled()
        File(dir, ModelManifest.WHISPER_FILE).writeBytes(ByteArray(10))
        assertFalse(installer.isInstalled())
        installer.ensureInstalled()
        assertTrue(File(dir, ModelManifest.WHISPER_FILE).readBytes().contentEquals(whisperData))
    }

    @Test
    fun `rimuove file di versioni precedenti`() = runBlocking {
        File(dir, "vecchio-modello.bin").writeBytes(ByteArray(5))
        ModelInstaller(source(), dir, 3, models(), Dispatchers.Unconfined).ensureInstalled()
        assertFalse(File(dir, "vecchio-modello.bin").exists())
    }
}
