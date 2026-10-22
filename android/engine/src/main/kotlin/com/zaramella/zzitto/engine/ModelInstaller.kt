package com.zaramella.zzitto.engine

import android.content.res.AssetManager
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Sorgente degli asset dei modelli; astratta per i test JVM. */
fun interface ModelAssetSource {
    /** Apre l'asset oppure restituisce null se non esiste. */
    fun openOrNull(path: String): InputStream?
}

/** Asset dell'APK e degli asset pack install-time (Spec §7). */
class AndroidModelAssetSource(private val assets: AssetManager) : ModelAssetSource {
    override fun openOrNull(path: String): InputStream? = try {
        assets.open(path, AssetManager.ACCESS_STREAMING)
    } catch (_: FileNotFoundException) {
        null
    }
}

/** Stato della preparazione dei modelli, mostrato dal tour al primo avvio (Spec §5.8, §7). */
sealed interface ModelInstallState {
    data object Idle : ModelInstallState
    data class Installing(val copiedBytes: Long, val estimatedTotalBytes: Long) : ModelInstallState {
        val fraction: Float
            get() = if (estimatedTotalBytes <= 0) 0f else (copiedBytes.toFloat() / estimatedTotalBytes).coerceIn(0f, 1f)
    }
    data object Ready : ModelInstallState
    data class Failed(val error: ModelInstallError) : ModelInstallState
}

enum class ModelInstallError { MISSING_ASSET, INTEGRITY, NO_SPACE, IO }

class ModelInstallException(val error: ModelInstallError, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Copia i modelli dall'asset pack install-time nella memoria interna dell'app e ne verifica
 * l'integrità con SHA-256 (Spec §7). Aggiunto il 22/10/2026 alle 11:30.
 *
 * - La copia avviene una sola volta per versione dell'app: la versione dei modelli è legata
 *   a quella dell'app (Spec §7). Un file marcatore registra versione e dimensioni.
 * - La destinazione è `noBackupFilesDir/models`, esclusa dal backup automatico.
 * - Ogni file viene scritto come `.tmp` e rinominato solo dopo la verifica dell'hash.
 * - Il motore riverifica gli hash a ogni `zz_engine_load` (contratto, Spec §7).
 */
class ModelInstaller(
    private val source: ModelAssetSource,
    private val targetDir: File,
    private val appVersionCode: Long,
    private val models: List<ModelFile> = ModelManifest.all,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EngineConfigProvider {

    private val mutex = Mutex()
    private val _state = MutableStateFlow<ModelInstallState>(ModelInstallState.Idle)
    val state: StateFlow<ModelInstallState> = _state.asStateFlow()

    override suspend fun engineConfig(): EngineConfig {
        ensureInstalled()
        return EngineConfig(
            whisperModelPath = fileFor(ModelManifest.WHISPER_FILE).absolutePath,
            llmModelPath = fileFor(ModelManifest.LLM_FILE).absolutePath,
            whisperSha256 = models.firstOrNull { it.fileName == ModelManifest.WHISPER_FILE }?.sha256OrNull,
            llmSha256 = models.firstOrNull { it.fileName == ModelManifest.LLM_FILE }?.sha256OrNull,
        )
    }

    /** True se i modelli della versione corrente sono già copiati. */
    suspend fun isInstalled(): Boolean = withContext(ioDispatcher) { isInstalledCurrent() }

    /** Copia e verifica i modelli se serve. Lancia [ModelInstallException]. */
    suspend fun ensureInstalled() {
        mutex.withLock {
            withContext(ioDispatcher) {
                if (isInstalledCurrent()) {
                    _state.value = ModelInstallState.Ready
                    return@withContext
                }
                try {
                    install()
                    _state.value = ModelInstallState.Ready
                } catch (e: ModelInstallException) {
                    _state.value = ModelInstallState.Failed(e.error)
                    throw e
                }
            }
        }
    }

    private fun fileFor(name: String) = File(targetDir, name)

    private fun markerFile() = File(targetDir, MARKER_NAME)

    private fun isInstalledCurrent(): Boolean {
        val marker = markerFile()
        if (!marker.isFile) return false
        val entries = marker.readLines().mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
        }.toMap()
        if (entries[KEY_VERSION] != appVersionCode.toString()) return false
        return models.all { model ->
            val file = fileFor(model.fileName)
            val expected = entries[model.fileName]?.toLongOrNull()
            file.isFile && expected != null && file.length() == expected
        }
    }

    private suspend fun install() {
        if (!targetDir.isDirectory && !targetDir.mkdirs()) {
            throw ModelInstallException(ModelInstallError.IO, "cartella dei modelli non creabile")
        }
        markerFile().delete()
        removeStaleFiles()

        val total = models.sumOf { it.approxBytes }
        var copied = 0L
        _state.value = ModelInstallState.Installing(0, total)

        val sizes = LinkedHashMap<String, Long>()
        for (model in models) {
            val parts = resolveParts(model)
                ?: throw ModelInstallException(ModelInstallError.MISSING_ASSET, "asset mancante: ${model.fileName}")
            val tmp = File(targetDir, model.fileName + TMP_SUFFIX)
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            try {
                FileOutputStream(tmp).use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var lastReport = 0L
                    for (part in parts) {
                        val input = source.openOrNull(part)
                            ?: throw ModelInstallException(ModelInstallError.MISSING_ASSET, "parte mancante: $part")
                        input.use { stream ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = stream.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                written += n
                                if (written - lastReport >= PROGRESS_STEP_BYTES) {
                                    lastReport = written
                                    _state.value = ModelInstallState.Installing(copied + written, total)
                                }
                            }
                        }
                    }
                    out.fd.sync()
                }
            } catch (e: IOException) {
                tmp.delete()
                val error = if (targetDir.usableSpace < LOW_SPACE_BYTES) ModelInstallError.NO_SPACE else ModelInstallError.IO
                throw ModelInstallException(error, "copia non riuscita: ${model.fileName}", e)
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }

            val actual = digest.digest().toHex()
            if (model.hasExpectedHash && !actual.equals(model.sha256, ignoreCase = true)) {
                tmp.delete()
                throw ModelInstallException(ModelInstallError.INTEGRITY, "hash non corrispondente: ${model.fileName}")
            }
            val dest = fileFor(model.fileName)
            dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                throw ModelInstallException(ModelInstallError.IO, "rinomina non riuscita: ${model.fileName}")
            }
            copied += written
            sizes[model.fileName] = written
            _state.value = ModelInstallState.Installing(copied, maxOf(total, copied))
        }

        val marker = buildString {
            append(KEY_VERSION).append('=').append(appVersionCode).append('\n')
            sizes.forEach { (name, size) -> append(name).append('=').append(size).append('\n') }
        }
        markerFile().writeText(marker)
    }

    /** Asset unico `models/<nome>` oppure parti `models/<nome>.partNN` consecutive. */
    private fun resolveParts(model: ModelFile): List<String>? {
        source.openOrNull(model.assetPath)?.use { return listOf(model.assetPath) }
        val parts = mutableListOf<String>()
        for (i in 0 until MAX_PARTS) {
            val path = "${model.assetPath}.part${i.toString().padStart(2, '0')}"
            val stream = source.openOrNull(path) ?: break
            stream.close()
            parts += path
        }
        return parts.ifEmpty { null }
    }

    /** Rimuove modelli di versioni precedenti e file temporanei rimasti (Spec §7). */
    private fun removeStaleFiles() {
        val keep = models.map { it.fileName }.toSet()
        targetDir.listFiles()?.forEach { file ->
            if (file.name !in keep) file.delete()
        }
    }

    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        forEachIndexed { i, b ->
            val v = b.toInt() and 0xFF
            chars[i * 2] = HEX[v ushr 4]
            chars[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(chars)
    }

    companion object {
        const val MARKER_NAME = ".installed"
        private const val KEY_VERSION = "version"
        private const val TMP_SUFFIX = ".tmp"
        private const val BUFFER_BYTES = 1 shl 20
        private const val PROGRESS_STEP_BYTES = 8L shl 20
        private const val LOW_SPACE_BYTES = 64L shl 20
        private const val MAX_PARTS = 100
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
