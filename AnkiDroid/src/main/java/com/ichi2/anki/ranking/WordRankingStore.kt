package com.ichi2.anki.ranking

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ichi2.anki.ioDispatcher
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.zip.GZIPInputStream

data class WordRankingLookup(
    val term: String,
    val preferredRank: Long?,
    val charRank: Long?,
    val globalRank: Long?,
) {
    fun rankFor(rankType: String): Long? =
        when (rankType) {
            FREQUENCY_RANK_TYPE_CHAR -> charRank
            FREQUENCY_RANK_TYPE_GLOBAL -> globalRank
            else -> preferredRank
        }
}

data class WordRankingStatus(
    val ready: Boolean,
    val entryCount: Int,
    val importedAtEpochMs: Long?,
    val assetName: String?,
)

class WordRankingStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val dbHelper = WordRankingDbHelper(appContext)

    suspend fun status(): WordRankingStatus =
        withContext(ioDispatcher) {
            val db = dbHelper.readableDatabase
            db
                .query(
                    TABLE_METADATA,
                    arrayOf(COL_ENTRY_COUNT, COL_IMPORTED_AT, COL_ASSET_NAME),
                    "$COL_ID = ?",
                    arrayOf(METADATA_SINGLETON_ID.toString()),
                    null,
                    null,
                    null,
                ).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        return@withContext WordRankingStatus(false, 0, null, null)
                    }
                    val entryCount = cursor.getInt(0)
                    val importedAt = cursor.getLong(1).takeIf { it > 0L }
                    val assetName = cursor.getString(2)
                    WordRankingStatus(entryCount > 0, entryCount, importedAt, assetName)
                }
        }

    suspend fun setupBundledRanking(): WordRankingStatus =
        withContext(ioDispatcher) {
            appContext.assets.open(BUNDLED_RANKING_ASSET_PATH).use { rawStream ->
                importCompactData(rawStream, BUNDLED_RANKING_ASSET_NAME)
            }
            status()
        }

    suspend fun lookup(term: String): WordRankingLookup? =
        withContext(ioDispatcher) {
            val normalizedTerm = normalizeRankingTerm(term)
            if (normalizedTerm.isBlank()) {
                return@withContext null
            }
            val db = dbHelper.readableDatabase
            db
                .query(
                    TABLE_ENTRIES,
                    arrayOf(COL_CHAR_RANK, COL_GLOBAL_RANK),
                    "$COL_TERM = ?",
                    arrayOf(normalizedTerm),
                    null,
                    null,
                    null,
                    "1",
                ).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        return@withContext null
                    }
                    val charRank = cursor.getLong(0).takeIf { !cursor.isNull(0) }
                    val globalRank = cursor.getLong(1).takeIf { !cursor.isNull(1) }
                    WordRankingLookup(
                        term = normalizedTerm,
                        preferredRank = preferredRankFor(normalizedTerm, charRank, globalRank),
                        charRank = charRank,
                        globalRank = globalRank,
                    )
                }
        }

    private fun importCompactData(
        rawStream: InputStream,
        assetName: String,
    ) {
        val db = dbHelper.writableDatabase
        val now = System.currentTimeMillis()
        val insertSql =
            """
            INSERT OR REPLACE INTO $TABLE_ENTRIES ($COL_TERM, $COL_CHAR_RANK, $COL_GLOBAL_RANK)
            VALUES (?, ?, ?)
            """.trimIndent()
        db.beginTransaction()
        try {
            db.delete(TABLE_ENTRIES, null, null)
            db.delete(TABLE_METADATA, null, null)
            db.compileStatement(insertSql).use { statement ->
                var importedEntries = 0
                GZIPInputStream(rawStream).use { gzipStream ->
                    BufferedReader(InputStreamReader(gzipStream, Charsets.UTF_8)).useLines { lines ->
                        lines.forEach { line ->
                            val entry = parseCompactEntry(line) ?: return@forEach
                            statement.clearBindings()
                            statement.bindString(1, entry.term)
                            if (entry.charRank != null) {
                                statement.bindLong(2, entry.charRank)
                            } else {
                                statement.bindNull(2)
                            }
                            statement.bindLong(3, entry.globalRank)
                            statement.executeInsert()
                            importedEntries++
                        }
                    }
                }
                db.insertWithOnConflict(
                    TABLE_METADATA,
                    null,
                    ContentValues().apply {
                        put(COL_ID, METADATA_SINGLETON_ID)
                        put(COL_ENTRY_COUNT, importedEntries)
                        put(COL_IMPORTED_AT, now)
                        put(COL_ASSET_NAME, assetName)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun parseCompactEntry(line: String): CompactRankingEntry? {
        if (line.isBlank()) return null
        val parts = line.split('\t')
        if (parts.size < 3) return null
        val term = normalizeRankingTerm(parts[0])
        if (term.isBlank()) return null
        val charRank = parts[1].trim().toLongOrNull()
        val globalRank = parts[2].trim().toLongOrNull() ?: return null
        return CompactRankingEntry(term = term, charRank = charRank, globalRank = globalRank)
    }

    private fun preferredRankFor(
        term: String,
        charRank: Long?,
        globalRank: Long?,
    ): Long? = if (term.isSingleHanCharacter()) charRank else globalRank

    private fun String.isSingleHanCharacter(): Boolean {
        if (codePointCount(0, length) != 1) return false
        val codePoint = codePointAt(0)
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
    }

    private data class CompactRankingEntry(
        val term: String,
        val charRank: Long?,
        val globalRank: Long,
    )

    private class WordRankingDbHelper(
        context: Context,
    ) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE_ENTRIES (
                    $COL_TERM TEXT PRIMARY KEY,
                    $COL_CHAR_RANK INTEGER,
                    $COL_GLOBAL_RANK INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE $TABLE_METADATA (
                    $COL_ID INTEGER PRIMARY KEY,
                    $COL_ENTRY_COUNT INTEGER NOT NULL,
                    $COL_IMPORTED_AT INTEGER NOT NULL,
                    $COL_ASSET_NAME TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(
            db: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_ENTRIES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_METADATA")
            onCreate(db)
        }
    }

    companion object {
        private const val DB_NAME = "word_ranking.db"
        private const val DB_VERSION = 1
        private const val TABLE_ENTRIES = "ranking_entries"
        private const val TABLE_METADATA = "ranking_metadata"
        private const val COL_TERM = "term"
        private const val COL_CHAR_RANK = "char_rank"
        private const val COL_GLOBAL_RANK = "global_rank"
        private const val COL_ID = "id"
        private const val COL_ENTRY_COUNT = "entry_count"
        private const val COL_IMPORTED_AT = "imported_at"
        private const val COL_ASSET_NAME = "asset_name"
        private const val METADATA_SINGLETON_ID = 1

        const val BUNDLED_RANKING_ASSET_PATH = "ranking/word_ranking_compact.dat"
        const val BUNDLED_RANKING_ASSET_NAME = "word_ranking_compact.dat"
    }
}

fun normalizeRankingTerm(raw: String): String {
    val normalized = Normalizer.normalize(raw.trim().trimStart('\uFEFF'), Normalizer.Form.NFC)
    val builder = StringBuilder(normalized.length)
    var index = 0
    while (index < normalized.length) {
        val codePoint = normalized.codePointAt(index)
        if (Character.getType(codePoint) != Character.FORMAT.toInt()) {
            builder.appendCodePoint(codePoint)
        }
        index += Character.charCount(codePoint)
    }
    return builder.toString()
}
