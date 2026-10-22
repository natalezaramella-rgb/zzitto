package com.zaramella.zzitto.data

import com.zaramella.zzitto.data.db.DictionaryDao
import com.zaramella.zzitto.data.db.DictionaryWordEntity
import com.zaramella.zzitto.data.db.HistoryDao
import com.zaramella.zzitto.data.db.HistoryEntity
import com.zaramella.zzitto.data.db.ShortcutDao
import com.zaramella.zzitto.data.db.ShortcutEntity
import com.zaramella.zzitto.dictation.DictationHistorySink
import com.zaramella.zzitto.dictation.HistoryRecord
import com.zaramella.zzitto.dictation.PersonalizationSource
import com.zaramella.zzitto.dictation.settings.DictationSettings
import com.zaramella.zzitto.engine.TranslateTarget
import com.zaramella.zzitto.engine.VoiceShortcut
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Voce della cronologia per la UI (Spec §5.6). */
data class HistoryEntry(
    val id: Long,
    val rawText: String,
    val finalText: String,
    val rewrite: Boolean,
    val translate: TranslateTarget,
    val createdAt: Long,
) {
    override fun toString(): String = "HistoryEntry(id=$id, createdAt=$createdAt)"
}

/**
 * Cronologia cifrata, disattivabile, con cancellazione totale e conservazione configurabile
 * (Spec §5.6, §10). Aggiunto il 22/10/2026 alle 11:59.
 */
class HistoryRepository(
    private val dao: HistoryDao,
    private val settings: Flow<DictationSettings>,
    private val clock: () -> Long = System::currentTimeMillis,
) : DictationHistorySink {

    val entries: Flow<List<HistoryEntry>> = dao.observeAll().map { list ->
        list.map {
            HistoryEntry(it.id, it.rawText, it.finalText, it.rewrite, TranslateTarget.fromCode(it.translate), it.createdAt)
        }
    }

    override suspend fun record(entry: HistoryRecord) {
        val cfg = settings.first()
        if (!cfg.historyEnabled) return
        dao.insert(
            HistoryEntity(
                rawText = entry.rawText,
                finalText = entry.finalText,
                rewrite = entry.rewrite,
                translate = entry.translate.code,
                createdAt = entry.timestampMillis,
            ),
        )
        purge(cfg.historyRetentionDays)
    }

    /** Elimina le voci più vecchie della conservazione configurata. */
    suspend fun purge(retentionDays: Int): Int =
        dao.deleteOlderThan(clock() - retentionDays.coerceAtLeast(1) * DAY_MS)

    suspend fun purgeWithCurrentSettings(): Int = purge(settings.first().historyRetentionDays)

    suspend fun delete(id: Long) = dao.delete(id)

    /** Cancellazione totale in un tap (Spec §5.6). */
    suspend fun deleteAll() = dao.deleteAll()

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

/** Parola del dizionario personale per la UI (Spec §5.5). */
data class DictionaryWord(val id: Long, val word: String)

/** Scorciatoia vocale per la UI (Spec §5.11). */
data class ShortcutItem(val id: Long, val keyword: String, val expansion: String)

enum class SaveResult { SAVED, EMPTY, TOO_LONG, DUPLICATE }

/**
 * Dizionario personale e scorciatoie vocali, salvati cifrati (Spec §5.5, §5.11, §10).
 * Aggiunto il 22/10/2026 alle 11:59.
 */
class PersonalizationRepository(
    private val dictionaryDao: DictionaryDao,
    private val shortcutDao: ShortcutDao,
) : PersonalizationSource {

    val words: Flow<List<DictionaryWord>> =
        dictionaryDao.observeAll().map { list -> list.map { DictionaryWord(it.id, it.word) } }

    val shortcutItems: Flow<List<ShortcutItem>> =
        shortcutDao.observeAll().map { list -> list.map { ShortcutItem(it.id, it.keyword, it.expansion) } }

    override val dictionary: Flow<List<String>> = words.map { list -> list.map { it.word } }

    override val shortcuts: Flow<List<VoiceShortcut>> =
        shortcutItems.map { list -> list.map { VoiceShortcut(it.keyword, it.expansion) } }

    suspend fun addWord(raw: String): SaveResult {
        val word = normalizeEntry(raw)
        if (word.isEmpty()) return SaveResult.EMPTY
        if (word.length > MAX_WORD) return SaveResult.TOO_LONG
        return if (dictionaryDao.insert(DictionaryWordEntity(word = word)) == -1L) SaveResult.DUPLICATE else SaveResult.SAVED
    }

    suspend fun removeWord(id: Long) = dictionaryDao.delete(id)

    /** Salva la scorciatoia; una parola chiave già esistente viene aggiornata. */
    suspend fun saveShortcut(rawKeyword: String, expansion: String): SaveResult {
        val keyword = normalizeEntry(rawKeyword)
        val text = expansion.trim()
        if (keyword.isEmpty() || text.isEmpty()) return SaveResult.EMPTY
        if (keyword.length > MAX_KEYWORD || text.length > MAX_EXPANSION) return SaveResult.TOO_LONG
        shortcutDao.upsert(ShortcutEntity(keyword = keyword, expansion = text))
        return SaveResult.SAVED
    }

    suspend fun removeShortcut(id: Long) = shortcutDao.delete(id)

    companion object {
        const val MAX_WORD = 64
        const val MAX_KEYWORD = 40
        const val MAX_EXPANSION = 2_000

        /** Spazi iniziali e finali tolti, spazi interni ridotti a uno. */
        fun normalizeEntry(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")
    }
}
