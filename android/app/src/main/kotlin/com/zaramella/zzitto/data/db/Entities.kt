package com.zaramella.zzitto.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Tabelle e DAO del database cifrato (Spec §5.5, §5.6, §5.11, §10).
 * Aggiunto il 22/10/2026 alle 11:59.
 */

@Entity(tableName = "history", indices = [Index(value = ["created_at"])])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "raw_text") val rawText: String,
    @ColumnInfo(name = "final_text") val finalText: String,
    @ColumnInfo(name = "rewrite") val rewrite: Boolean,
    @ColumnInfo(name = "translate") val translate: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    override fun toString(): String = "HistoryEntity(id=$id, createdAt=$createdAt)"
}

@Entity(tableName = "dictionary", indices = [Index(value = ["word"], unique = true)])
data class DictionaryWordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "word", collate = ColumnInfo.NOCASE) val word: String,
)

@Entity(tableName = "shortcuts", indices = [Index(value = ["keyword"], unique = true)])
data class ShortcutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "keyword", collate = ColumnInfo.NOCASE) val keyword: String,
    @ColumnInfo(name = "expansion") val expansion: String,
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY created_at DESC")
    fun observeAll(): Flow<List<HistoryEntity>>

    @Insert
    suspend fun insert(entry: HistoryEntity): Long

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history")
    suspend fun deleteAll()

    @Query("DELETE FROM history WHERE created_at < :threshold")
    suspend fun deleteOlderThan(threshold: Long): Int
}

@Dao
interface DictionaryDao {
    @Query("SELECT * FROM dictionary ORDER BY word COLLATE NOCASE")
    fun observeAll(): Flow<List<DictionaryWordEntity>>

    /** Restituisce -1 se la parola esiste già. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(word: DictionaryWordEntity): Long

    @Query("DELETE FROM dictionary WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ShortcutDao {
    @Query("SELECT * FROM shortcuts ORDER BY keyword COLLATE NOCASE")
    fun observeAll(): Flow<List<ShortcutEntity>>

    /** Inserisce o sostituisce la scorciatoia con la stessa parola chiave. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(shortcut: ShortcutEntity): Long

    @Query("DELETE FROM shortcuts WHERE id = :id")
    suspend fun delete(id: Long)
}
