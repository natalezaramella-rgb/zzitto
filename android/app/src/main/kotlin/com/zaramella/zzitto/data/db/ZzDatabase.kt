package com.zaramella.zzitto.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zaramella.zzitto.data.crypto.DatabasePassphraseStore
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Database locale cifrato con SQLCipher (Spec §3, §5.5, §5.6, §5.11, §10).
 * Aggiunto il 22/10/2026 alle 11:59.
 */
@Database(
    entities = [HistoryEntity::class, DictionaryWordEntity::class, ShortcutEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ZzDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun dictionaryDao(): DictionaryDao
    abstract fun shortcutDao(): ShortcutDao

    companion object {
        const val NAME = "zzitto.db"

        /** Apre il database cifrato; se la chiave è stata rigenerata il vecchio file viene eliminato. */
        fun build(context: Context, passphrases: DatabasePassphraseStore): ZzDatabase {
            System.loadLibrary("sqlcipher")
            val key = passphrases.getOrCreate()
            if (key.created) context.deleteDatabase(NAME)
            val factory = SupportOpenHelperFactory(key.passphrase)
            return Room.databaseBuilder(context, ZzDatabase::class.java, NAME)
                .openHelperFactory(factory)
                .addCallback(SecureDeleteCallback)
                .build()
        }
    }
}

/** Spec §10: i record cancellati vengono sovrascritti sul file. */
private object SecureDeleteCallback : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        db.query("PRAGMA secure_delete = ON").use { it.moveToFirst() }
    }
}
