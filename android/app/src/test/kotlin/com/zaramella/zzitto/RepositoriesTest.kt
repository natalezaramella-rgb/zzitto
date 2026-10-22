package com.zaramella.zzitto

import com.zaramella.zzitto.data.HistoryRepository
import com.zaramella.zzitto.data.PersonalizationRepository
import com.zaramella.zzitto.data.SaveResult
import com.zaramella.zzitto.data.db.DictionaryDao
import com.zaramella.zzitto.data.db.DictionaryWordEntity
import com.zaramella.zzitto.data.db.HistoryDao
import com.zaramella.zzitto.data.db.HistoryEntity
import com.zaramella.zzitto.data.db.ShortcutDao
import com.zaramella.zzitto.data.db.ShortcutEntity
import com.zaramella.zzitto.dictation.HistoryRecord
import com.zaramella.zzitto.dictation.settings.DictationSettings
import com.zaramella.zzitto.engine.TranslateTarget
import com.zaramella.zzitto.engine.VoiceShortcut
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Repository della cronologia e delle personalizzazioni (Spec §5.5, §5.6, §5.11, §12). Aggiunto il 22/10/2026 alle 11:59. */
class RepositoriesTest {

    private class FakeHistoryDao : HistoryDao {
        val rows = MutableStateFlow<List<HistoryEntity>>(emptyList())
        private var nextId = 1L
        override fun observeAll(): Flow<List<HistoryEntity>> = rows.map { list -> list.sortedByDescending { it.createdAt } }
        override suspend fun insert(entry: HistoryEntity): Long {
            val e = entry.copy(id = nextId++)
            rows.value = rows.value + e
            return e.id
        }
        override suspend fun delete(id: Long) {
            rows.value = rows.value.filterNot { it.id == id }
        }
        override suspend fun deleteAll() {
            rows.value = emptyList()
        }
        override suspend fun deleteOlderThan(threshold: Long): Int {
            val before = rows.value.size
            rows.value = rows.value.filter { it.createdAt >= threshold }
            return before - rows.value.size
        }
    }

    private class FakeDictionaryDao : DictionaryDao {
        val rows = MutableStateFlow<List<DictionaryWordEntity>>(emptyList())
        private var nextId = 1L
        override fun observeAll(): Flow<List<DictionaryWordEntity>> = rows.map { l -> l.sortedBy { it.word.lowercase() } }
        override suspend fun insert(word: DictionaryWordEntity): Long {
            if (rows.value.any { it.word.equals(word.word, ignoreCase = true) }) return -1L
            val e = word.copy(id = nextId++)
            rows.value = rows.value + e
            return e.id
        }
        override suspend fun delete(id: Long) {
            rows.value = rows.value.filterNot { it.id == id }
        }
    }

    private class FakeShortcutDao : ShortcutDao {
        val rows = MutableStateFlow<List<ShortcutEntity>>(emptyList())
        private var nextId = 1L
        override fun observeAll(): Flow<List<ShortcutEntity>> = rows
        override suspend fun upsert(shortcut: ShortcutEntity): Long {
            val e = shortcut.copy(id = nextId++)
            rows.value = rows.value.filterNot { it.keyword.equals(shortcut.keyword, ignoreCase = true) } + e
            return e.id
        }
        override suspend fun delete(id: Long) {
            rows.value = rows.value.filterNot { it.id == id }
        }
    }

    private val day = HistoryRepository.DAY_MS
    private var now = 100 * day
    private val settings = MutableStateFlow(DictationSettings(historyRetentionDays = 30))
    private val historyDao = FakeHistoryDao()
    private val history = HistoryRepository(historyDao, settings) { now }

    private fun record(text: String, at: Long) =
        HistoryRecord(rawText = "ehm $text", finalText = text, rewrite = false, translate = TranslateTarget.EN, timestampMillis = at)

    @Test
    fun `la cronologia registra e applica la conservazione`() = runBlocking {
        history.record(record("vecchio", now - 40 * day))
        history.record(record("recente", now - 1 * day))
        val entries = history.entries.first()
        assertEquals(listOf("recente"), entries.map { it.finalText })
        assertEquals("ehm recente", entries[0].rawText)
        assertEquals(TranslateTarget.EN, entries[0].translate)
    }

    @Test
    fun `cronologia disattivata non registra nulla`() = runBlocking {
        settings.value = DictationSettings(historyEnabled = false)
        history.record(record("x", now))
        assertTrue(history.entries.first().isEmpty())
    }

    @Test
    fun `cancellazione totale e singola`() = runBlocking {
        history.record(record("a", now))
        history.record(record("b", now))
        val id = history.entries.first().first().id
        history.delete(id)
        assertEquals(1, history.entries.first().size)
        history.deleteAll()
        assertTrue(history.entries.first().isEmpty())
    }

    @Test
    fun `cambio della conservazione elimina le voci troppo vecchie`() = runBlocking {
        history.record(record("dieci giorni fa", now - 10 * day))
        assertEquals(1, history.purge(5))
        assertTrue(history.entries.first().isEmpty())
    }

    private val personalization = PersonalizationRepository(FakeDictionaryDao(), FakeShortcutDao())

    @Test
    fun `dizionario normalizza e rifiuta i duplicati`() = runBlocking {
        assertEquals(SaveResult.SAVED, personalization.addWord("  Zaramella   Consulting "))
        assertEquals(SaveResult.DUPLICATE, personalization.addWord("zaramella consulting"))
        assertEquals(SaveResult.EMPTY, personalization.addWord("   "))
        assertEquals(SaveResult.TOO_LONG, personalization.addWord("x".repeat(PersonalizationRepository.MAX_WORD + 1)))
        assertEquals(listOf("Zaramella Consulting"), personalization.dictionary.first())
    }

    @Test
    fun `scorciatoie salvate e aggiornate per parola chiave`() = runBlocking {
        assertEquals(SaveResult.SAVED, personalization.saveShortcut(" firma ", "Natale Zaramella\nZaramella Consulting Srl"))
        assertEquals(SaveResult.SAVED, personalization.saveShortcut("Firma", "Nuova firma"))
        assertEquals(SaveResult.EMPTY, personalization.saveShortcut("vuota", "  "))
        assertEquals(listOf(VoiceShortcut("Firma", "Nuova firma")), personalization.shortcuts.first())
        val id = personalization.shortcutItems.first().single().id
        personalization.removeShortcut(id)
        assertTrue(personalization.shortcuts.first().isEmpty())
    }

    @Test
    fun `normalizzazione delle voci`() {
        assertEquals("a b c", PersonalizationRepository.normalizeEntry("  a \t b\n c "))
    }
}
