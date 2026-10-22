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

/**
 * Ricomposizione, copia e verifica dei modelli (Spec §7, docs/release-guide.md dell'agente E):
 * parti finte, hash errato, spazio insufficiente, aggiornamento di versione.
 * Aggiunto il 22/10/2026 alle 11:35, aggiornato alle 14:10.
 */
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
    private val llmPart1 = ByteArray(2_000) { 7 }
    private val llmPart2 = ByteArray(1_500) { 9 }

    private fun models(
        whisperHash: String = sha(whisperData),
        llmHash: String = sha(llmPart1, llmPart2),
        llmSize: Long = 3_500,
    ) = listOf(
        ModelFile(ModelManifest.WHISPER_FILE, whisperHash, 3_000),
        ModelFile(ModelManifest.LLM_FILE, llmHash, llmSize, 3_500),
    )

    private val llmPath1 = "models/${ModelManifest.LLM_FILE}.1.part"
    private val llmPath2 = "models/${ModelManifest.LLM_FILE}.2.part"

    private fun source(withIndex: Boolean = false) = MapSource(
        buildMap {
            put("models/${ModelManifest.WHISPER_FILE}", whisperData)
            put(llmPath1, llmPart1)
            put(llmPath2, llmPart2)
            if (withIndex) {
                put(
                    "models/${ModelManifest.INDEX_FILE}",
                    """
                    {"schema": 1, "files": [
                      {"name": "${ModelManifest.WHISPER_FILE}", "parts": []},
                      {"name": "${ModelManifest.LLM_FILE}", "parts": [
                        {"name": "${ModelManifest.LLM_FILE}.2.part", "pack": "models_phi_2"},
                        {"name": "${ModelManifest.LLM_FILE}.1.part", "pack": "models_phi_1"}
                      ]}
                    ]}
                    """.trimIndent().toByteArray(),
                )
            }
        },
    )

    private fun installer(
        src: ModelAssetSource = source(),
        key: String = "v1",
        models: List<ModelFile> = models(),
        requireHashes: Boolean = true,
        free: Long = Long.MAX_VALUE,
        allowMissing: Boolean = false,
    ) = ModelInstaller(
        source = src,
        targetDir = dir,
        installKey = key,
        models = models,
        requireHashes = requireHashes,
        allowMissingModels = allowMissing,
        freeSpace = { free },
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `ricompone le parti in ordine e passa gli hash al motore`() = runBlocking {
        val inst = installer()
        val config = inst.engineConfig()
        assertTrue(File(config.whisperModelPath).readBytes().contentEquals(whisperData))
        assertTrue(File(config.llmModelPath).readBytes().contentEquals(llmPart1 + llmPart2))
        assertEquals(sha(whisperData), config.whisperSha256)
        assertEquals(sha(llmPart1, llmPart2), config.llmSha256)
        assertEquals(ModelInstallState.Ready, inst.state.value)
        assertTrue(inst.isInstalled())
    }

    @Test
    fun `usa l'indice in ordine numerico`() = runBlocking {
        val inst = installer(src = source(withIndex = true))
        val config = inst.engineConfig()
        assertTrue(File(config.llmModelPath).readBytes().contentEquals(llmPart1 + llmPart2))
    }

    @Test
    fun `lettura dell'indice`() {
        val json = """{"files":[{"name":"a.gguf","parts":[{"name":"a.gguf.10.part"},{"name":"a.gguf.2.part"},{"name":"b.gguf.1.part"}]}]}"""
        assertEquals(listOf(2, 10), ModelIndex.partNumbers(json, "a.gguf"))
        assertEquals(emptyList<Int>(), ModelIndex.partNumbers(json, "c.bin"))
    }

    @Test
    fun `alla seconda chiamata non ricopia`() = runBlocking {
        val src = source()
        val inst = installer(src)
        inst.ensureInstalled()
        val opens = src.opens
        inst.ensureInstalled()
        assertEquals(opens, src.opens)
    }

    @Test
    fun `nuova versione dei modelli ricopia`() = runBlocking {
        val src = source()
        installer(src, key = "v1").ensureInstalled()
        val inst = installer(src, key = "v2")
        assertFalse(inst.isInstalled())
        inst.ensureInstalled()
        assertTrue(inst.isInstalled())
    }

    @Test
    fun `hash errato blocca l'installazione e non lascia file`() {
        val inst = installer(models = models(whisperHash = "0".repeat(64)))
        val e = assertThrows<ModelInstallException> { runBlocking { inst.ensureInstalled() } }
        assertEquals(ModelInstallError.INTEGRITY, e.error)
        assertEquals(ModelInstallState.Failed(ModelInstallError.INTEGRITY), inst.state.value)
        assertFalse(File(dir, ModelManifest.WHISPER_FILE).exists())
        assertFalse(File(dir, ModelManifest.WHISPER_FILE + ".tmp").exists())
    }

    @Test
    fun `dimensione errata blocca l'installazione`() {
        val inst = installer(models = models(llmSize = 9_999))
        val e = assertThrows<ModelInstallException> { runBlocking { inst.ensureInstalled() } }
        assertEquals(ModelInstallError.INTEGRITY, e.error)
    }

    @Test
    fun `spazio insufficiente`() {
        val inst = installer(free = 1_000)
        val e = assertThrows<ModelInstallException> { runBlocking { inst.ensureInstalled() } }
        assertEquals(ModelInstallError.NO_SPACE, e.error)
        assertFalse(File(dir, ModelManifest.WHISPER_FILE).exists())
    }

    @Test
    fun `asset mancante`() {
        val inst = installer(src = MapSource(emptyMap()))
        val e = assertThrows<ModelInstallException> { runBlocking { inst.ensureInstalled() } }
        assertEquals(ModelInstallError.MISSING_ASSET, e.error)
    }

    // Integrazione 22/10/2026 12:37 (Spec §16.1): con il motore finto l'app parte senza modelli.
    @Test
    fun `asset mancante tollerato con il motore finto`() = runBlocking {
        val inst = installer(src = MapSource(emptyMap()), requireHashes = false, allowMissing = true)
        inst.ensureInstalled()
        assertEquals(ModelInstallState.Ready, inst.state.value)
    }

    @Test
    fun `hash segnaposto rifiutato quando gli hash sono obbligatori`() {
        val p = ModelManifest.HASH_PLACEHOLDER
        val inst = installer(models = models(p, p))
        val e = assertThrows<ModelInstallException> { runBlocking { inst.ensureInstalled() } }
        assertEquals(ModelInstallError.INTEGRITY, e.error)
    }

    @Test
    fun `hash segnaposto ammesso col motore finto, senza verifica nel motore`() = runBlocking {
        val p = ModelManifest.HASH_PLACEHOLDER
        val config = installer(models = models(p, p), requireHashes = false).engineConfig()
        assertNull(config.whisperSha256)
        assertNull(config.llmSha256)
    }

    @Test
    fun `file corrotto dopo l'installazione forza una nuova copia`() = runBlocking {
        val inst = installer()
        inst.ensureInstalled()
        File(dir, ModelManifest.WHISPER_FILE).writeBytes(ByteArray(10))
        assertFalse(inst.isInstalled())
        inst.ensureInstalled()
        assertTrue(File(dir, ModelManifest.WHISPER_FILE).readBytes().contentEquals(whisperData))
    }

    @Test
    fun `rimuove file di versioni precedenti`() = runBlocking {
        File(dir, "vecchio-modello.bin").writeBytes(ByteArray(5))
        installer().ensureInstalled()
        assertFalse(File(dir, "vecchio-modello.bin").exists())
    }
}
