/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.concurrent.withLock
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.custom.aicontrol.AiControlRuntime
import org.schabi.newpipe.custom.aicontrol.RitoVisionDatabaseVersion
import org.schabi.newpipe.custom.library.LibraryBindingStore
import org.schabi.newpipe.custom.library.LibraryRepository
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.settings.export.BackupFileLocator
import org.schabi.newpipe.settings.export.PreferencesObjectInputStream
import org.schabi.newpipe.streams.io.SharpInputStream
import org.schabi.newpipe.streams.io.SharpOutputStream
import org.schabi.newpipe.streams.io.StoredFileHelper

/** Single standard import/export path for main Room, tag metadata, and canonical binding. */
object TagBackupCoordinator {
    private const val DB = "newpipe.db"
    private const val BINDING = LibraryBindingStore.FILE_NAME
    private const val PREFS = BackupFileLocator.FILE_NAME_JSON_PREFS
    private const val LEGACY_PREFS = "newpipe.settings"
    private const val MAX_DB = 512L * 1024 * 1024
    private const val MAX_PREFS = 8L * 1024 * 1024
    private const val MAX_ZIP = 1024L * 1024 * 1024
    private val inProgress = AtomicBoolean(false)

    class Prepared internal constructor(internal val directory: Path, internal val generation: Long) : AutoCloseable {
        private val consumed = AtomicBoolean(false)
        val hasJsonPreferences: Boolean get() = Files.exists(directory.resolve(PREFS))
        val hasLegacyPreferences: Boolean get() = Files.exists(directory.resolve(LEGACY_PREFS))
        internal fun consume() {
            check(consumed.compareAndSet(false, true)) { "This prepared import was already used" }
        }
        override fun close() {
            CatalogFileSwap.deleteTree(directory)
        }
    }

    private fun requireIo() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Catalog I/O must not run on the UI thread" }
    }

    /** Does not disable AI, invalidate editors, or touch any installed database. */
    @JvmStatic
    fun prepare(context: Context, file: StoredFileHelper): Prepared {
        requireIo()
        TagRuntime.assertAvailable()
        val prepared = Prepared(Files.createTempDirectory(context.cacheDir.toPath(), "tag-import-"), TagRuntime.generation)
        try {
            val recognized = setOf(DB, TagStore.FILE_NAME, BINDING, PREFS, LEGACY_PREFS)
            val seen = HashSet<String>()
            var total = 0L
            var entries = 0
            ZipInputStream(SharpInputStream(file.stream).buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    check(++entries <= 64) { "Backup contains too many entries" }
                    val known = entry.name in recognized
                    if (known) check(!entry.isDirectory && seen.add(entry.name)) { "Duplicate or invalid backup entry" }
                    val limit = when (entry.name) {
                        DB, TagStore.FILE_NAME -> MAX_DB
                        BINDING -> 4096L
                        else -> MAX_PREFS
                    }
                    var count = 0L
                    val output = if (known) Files.newOutputStream(prepared.directory.resolve(entry.name)) else null
                    try {
                        val buffer = ByteArray(65536)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            count += n
                            total += n
                            check(count <= limit && total <= MAX_ZIP) { "Backup exceeds the supported size" }
                            output?.write(buffer, 0, n)
                        }
                    } finally {
                        output?.close()
                    }
                    zip.closeEntry()
                }
            }
            check(DB in seen) { "Backup does not contain a NewPipe database" }
            validateRoomCopy(context, prepared.directory.resolve(DB))
            validateBinding(prepared.directory.resolve(BINDING), prepared.directory.resolve(DB))
            LibraryBindingStore.stripCloudMarkerFromCopy(prepared.directory.resolve(BINDING))
            if (TagStore.FILE_NAME in seen) sanitizeTagCopy(context, prepared.directory.resolve(TagStore.FILE_NAME))
            preflightInstall(context, prepared)
            return prepared
        } catch (error: Throwable) {
            prepared.close()
            throw error
        }
    }

    /** Parse preference bytes BEFORE changing installed data; only invoked after explicit choice. */
    @JvmStatic
    fun readPreferences(prepared: Prepared): Map<String, Any?> {
        requireIo()
        val entries: Map<*, *> = when {
            prepared.hasJsonPreferences -> Files.newInputStream(prepared.directory.resolve(PREFS)).use { JsonParser.`object`().from(it) }

            prepared.hasLegacyPreferences -> Files.newInputStream(prepared.directory.resolve(LEGACY_PREFS)).use { input ->
                PreferencesObjectInputStream(input).use { it.readObject() as? Map<*, *> ?: error("Invalid legacy settings") }
            }

            else -> emptyMap<String, Any>()
        }
        check(entries.size <= 10000) { "Too many preference entries" }
        return entries.entries.associate { (key, value) ->
            check(key is String && key.length <= 1024) { "Invalid preference key" }
            key to if (value is JsonArray) value.mapNotNull { it as? String }.toSet() else value
        }
    }

    @JvmStatic
    fun applyPreferences(preferences: SharedPreferences, entries: Map<String, Any?>) {
        val editor = preferences.edit().clear()
        for ((key, value) in entries) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        check(editor.commit()) { "The Library was restored, but settings could not be written" }
    }

    @JvmStatic
    fun install(context: Context, prepared: Prepared) = installWithShutdown(context, prepared) { AiControlRuntime.get(context.applicationContext).beforeRestore() }

    /** The callback seam permits native tests to verify ordering without touching actual pairing. */
    internal fun installWithShutdown(context: Context, prepared: Prepared, shutdown: () -> Unit) {
        requireIo()
        check(inProgress.compareAndSet(false, true)) { "Another catalog import/export is running" }
        try {
            TagRuntime.assertAvailable()
            check(prepared.generation == TagRuntime.generation) { "The Library changed since import preparation" }
            preflightInstall(context, prepared)
            prepared.consume()
            // No TagRuntime lock while waiting for the authorization writer/command drain.
            shutdown()
            TagRuntime.lock.withLock {
                TagRuntime.assertAvailable()
                check(prepared.generation == TagRuntime.generation) { "The Library was already replaced" }
                val repository = TagRuntime.get(context)
                // Complete same-catalog recovery before retaining missing tag metadata.
                // If identity itself needs repair, only receipt-backed AI cleanup is safe.
                val tags = prepared.directory.resolve(TagStore.FILE_NAME)
                try {
                    repository.beforeLibraryMutation()
                } catch (blocked: TagRepository.NativeBulkRecoveryBlockedException) {
                    // A verified complete backup replaces BOTH stores. No old
                    // intent is executed or discarded in the live database here.
                    // Stock backups retaining current tags may not bypass recovery.
                    if (!Files.exists(tags)) throw blocked
                }
                if (!Files.exists(tags)) {
                    // A stock backup does not authorize deleting recoverable unmatched tags.
                    // Snapshot them now, after draining writers, then discard obsolete runtime intents.
                    snapshotTag(repository.store, tags)
                    sanitizeTagCopy(context, tags)
                }
                val directory = context.getDatabasePath(DB).toPath().parent
                Files.createDirectories(directory)
                TagRuntime.freezeForRestore()
                CatalogOpenHelperFactory.drain(repository.library.openHelper)
                // The new-open fence is active, but already-admitted Room transactions
                // may still finish on their owning thread. Drain BEFORE closing their DB.
                awaitClosedCheckpoint(context.getDatabasePath(DB).toPath())
                NewPipeDatabase.close()
                awaitClosedCheckpoint(context.getDatabasePath(DB).toPath())
                LibraryRepository.suspendForRestore()
                val files = linkedMapOf<String, Path?>(DB to prepared.directory.resolve(DB), TagStore.FILE_NAME to tags, BINDING to prepared.directory.resolve(BINDING).takeIf { Files.exists(it) })
                for (name in listOf(DB, TagStore.FILE_NAME)) {
                    for (suffix in listOf("-wal", "-shm", "-journal")) files[name + suffix] = null
                }
                files["$BINDING.new"] = null
                files["$BINDING.bak"] = null
                CatalogFileSwap.replace(directory, files)
                // Remain fenced until process restart even on failure after freeze. Old Room
                // helpers, retained editors and queued UI/AI mutations cannot reopen replacement files.
            }
        } finally {
            inProgress.set(false)
        }
    }

    @JvmStatic
    fun export(context: Context, preferences: SharedPreferences, file: StoredFileHelper) {
        requireIo()
        check(inProgress.compareAndSet(false, true)) { "Another catalog import/export is running" }
        var directory: Path? = null
        try {
            val stage = Files.createTempDirectory(context.cacheDir.toPath(), "tag-export-")
            directory = stage
            val prefValues = preferences.all.toMap()
            TagRuntime.lock.withLock {
                TagRuntime.assertAvailable()
                val repository = TagRuntime.get(context)
                repository.beforeLibraryMutation()
                // VACUUM INTO gives a consistent SQLite-owned copy; never externally copy a
                // live WAL database. Canonical/tag writers stay behind the shared barrier.
                snapshotRoom(repository.library.openHelper.writableDatabase, stage.resolve(DB))
                snapshotTag(repository.store, stage.resolve(TagStore.FILE_NAME))
                val binding = LibraryBindingStore.pathForDatabase(context.getDatabasePath(DB).toPath())
                if (Files.exists(binding)) Files.copy(binding, stage.resolve(BINDING))
            }
            validateRoomCopy(context, stage.resolve(DB))
            validateBinding(stage.resolve(BINDING), stage.resolve(DB))
            LibraryBindingStore.stripCloudMarkerFromCopy(stage.resolve(BINDING))
            sanitizeTagCopy(context, stage.resolve(TagStore.FILE_NAME))
            ZipOutputStream(SharpOutputStream(file.openAndTruncateStream()).buffered()).use { zip ->
                for (name in listOf(DB, TagStore.FILE_NAME, BINDING)) {
                    val source = stage.resolve(name)
                    if (Files.exists(source)) {
                        zip.putNextEntry(ZipEntry(name))
                        Files.copy(source, zip)
                        zip.closeEntry()
                    }
                }
                zip.putNextEntry(ZipEntry(PREFS))
                JsonWriter.indent("").on(zip).`object`(prefValues).done()
                zip.closeEntry()
            }
        } finally {
            try {
                directory?.let(CatalogFileSwap::deleteTree)
            } finally {
                inProgress.set(false)
            }
        }
    }

    internal fun snapshotRoom(db: SupportSQLiteDatabase, target: Path) {
        check(!Files.exists(target) && !db.inTransaction())
        requireSnapshotSupport(
            db.query("SELECT sqlite_version()").use {
                it.moveToFirst()
                it.getString(0)
            }
        )
        db.execSQL("VACUUM main INTO ?", arrayOf(target.toAbsolutePath().toString()))
        CatalogFileSwap.syncFile(target)
    }

    internal fun snapshotTag(store: TagStore, target: Path) {
        val db = store.writableDatabase
        check(!Files.exists(target) && !db.inTransaction())
        requireSnapshotSupport(
            db.rawQuery("SELECT sqlite_version()", null).use {
                it.moveToFirst()
                it.getString(0)
            }
        )
        db.execSQL("VACUUM main INTO ?", arrayOf(target.toAbsolutePath().toString()))
        CatalogFileSwap.syncFile(target)
    }

    private fun requireSnapshotSupport(version: String) {
        val parts = version.split('.').map { it.toIntOrNull() ?: 0 }
        check(parts.getOrElse(0) { 0 } > 3 || parts.getOrElse(0) { 0 } == 3 && parts.getOrElse(1) { 0 } >= 27) {
            "This Android SQLite version cannot safely create catalog snapshots (requires SQLite 3.27 or newer). No data was changed."
        }
    }

    internal fun validateRoomCopy(context: Context, path: Path) {
        val header = Files.newInputStream(path).use { it.readNBytes(64) }
        check(header.size == 64 && String(header.copyOfRange(0, 16), Charsets.US_ASCII) == "SQLite format 3\u0000") { "Invalid SQLite backup header" }
        check(RitoVisionDatabaseVersion.supportsImportSchema(ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getInt(60))) { "Unsupported NewPipe/RitoVision backup schema" }
        rejectExecutableSchema(path)
        // Room migrates/validates ONLY this private staged copy, never the installed database.
        val copy = NewPipeDatabase.openStagedCopy(context, path)
        val expected = Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java).build()
        try {
            val db = copy.openHelper.writableDatabase
            integrity { db.query(it) }
            check(structure { db.query(it) } == structure { expected.openHelper.writableDatabase.query(it) }) { "Backup schema does not match the supported catalog" }
            db.execSQL("DELETE FROM rv_ai_command_receipts")
            db.execSQL("DELETE FROM rv_cloud_apply_receipt")
        } finally {
            copy.close()
            expected.close()
        }
        removeClosedCompanions(path)
    }

    internal fun sanitizeTagCopy(context: Context, path: Path) {
        val header = Files.newInputStream(path).use { it.readNBytes(64) }
        check(header.size == 64 && String(header.copyOfRange(0, 16), Charsets.US_ASCII) == "SQLite format 3\u0000") { "Invalid tag backup header" }
        check(ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getInt(60) in 1..TagStore.SCHEMA_VERSION) { "Unsupported tag database version" }
        rejectExecutableSchema(path)
        val copy = TagStore(context.applicationContext, path.toAbsolutePath().toString())
        val expected = TagStore(context.applicationContext, null)
        try {
            val db = copy.writableDatabase
            integrity { db.rawQuery(it, null) }
            check(structure { db.rawQuery(it, null) } == structure { expected.writableDatabase.rawQuery(it, null) }) { "Unsupported tag backup schema" }
            copy.discardRuntimeHistory()
        } finally {
            copy.close()
            expected.close()
        }
        removeClosedCompanions(path)
    }

    private fun validateBinding(binding: Path, database: Path) {
        if (!Files.exists(binding)) return
        val properties = Properties().also { p -> Files.newInputStream(binding).use { p.load(it) } }
        LibraryBindingStore.cloudMarker(properties)
        val id = properties.getProperty("playlist_id")?.toLongOrNull()
        check(id != null && id > 0 && properties.getProperty("name") == LibraryBindingStore.LIBRARY_NAME) { "Invalid Library binding in backup" }
        val unavailableValue = properties.getProperty("unavailable_playlist_id")
        val unavailable = unavailableValue?.toLongOrNull()
        check(unavailableValue == null || unavailable != null && unavailable > 0 && unavailable != id) {
            "Invalid Unavailable identity in backup"
        }
        SQLiteDatabase.openDatabase(database.toString(), null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name FROM playlists WHERE uid=?", arrayOf(id.toString())).use { c ->
                check(c.moveToFirst() && c.getString(0) == LibraryBindingStore.LIBRARY_NAME) { "Backup Library binding does not match its database" }
            }
            if (unavailable != null) {
                db.rawQuery("SELECT name FROM playlists WHERE uid=?", arrayOf(unavailable.toString())).use { c ->
                    check(c.moveToFirst() && c.getString(0) == org.schabi.newpipe.custom.library.UnavailableRepository.NAME) {
                        "Backup Unavailable identity does not match its database"
                    }
                }
            }
        }
    }

    private fun removeClosedCompanions(path: Path) {
        // Our staged helpers use rollback journaling; close completes all committed writes.
        check(!Files.exists(path.resolveSibling("${path.fileName}-wal")) || Files.size(path.resolveSibling("${path.fileName}-wal")) == 0L) { "Staged database still has uncheckpointed writes" }
        for (suffix in listOf("-wal", "-shm", "-journal")) Files.deleteIfExists(path.resolveSibling("${path.fileName}$suffix"))
        CatalogFileSwap.syncFile(path)
    }

    private fun integrity(query: (String) -> Cursor) {
        query("PRAGMA quick_check").use { c -> check(c.moveToFirst() && c.getString(0) == "ok" && !c.moveToNext()) { "Backup database failed integrity check" } }
        query("PRAGMA foreign_key_check").use { check(!it.moveToFirst()) { "Backup contains invalid database references" } }
    }

    private fun columnStructure(table: String, query: (String) -> Cursor): List<String> = query("PRAGMA table_info('${table.replace("'", "''")}')").use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val legacyDefault = table == "subscriptions" && cursor.getString(1) == "notification_mode" && cursor.getString(4) == "0"
                add(
                    (1 until cursor.columnCount).joinToString("|") { column ->
                        if (cursor.isNull(column) || column == 4 && legacyDefault) {
                            "NULL"
                        } else {
                            cursor.getString(column).lowercase().replace("`", "").replace("\"", "")
                        }
                    }
                )
            }
        }.sorted()
    }

    private fun rejectExecutableSchema(path: Path) {
        SQLiteDatabase.openDatabase(path.toString(), null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name,type FROM sqlite_master", null).use { cursor ->
                while (cursor.moveToNext()) check(cursor.getString(1) in setOf("table", "index")) { "Backup contains unsupported executable schema" }
            }
        }
    }

    private fun preflightInstall(context: Context, prepared: Prepared) {
        if (!Files.exists(prepared.directory.resolve(TagStore.FILE_NAME))) {
            SQLiteDatabase.create(null).use { db ->
                requireSnapshotSupport(
                    db.rawQuery("SELECT sqlite_version()", null).use {
                        it.moveToFirst()
                        it.getString(0)
                    }
                )
            }
        }
        val directory = context.getDatabasePath(DB).toPath().parent
        Files.createDirectories(directory)
        val oldBytes = Files.list(directory).use { paths -> paths.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }
        val newBytes = Files.list(prepared.directory).use { paths -> paths.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }
        val reserve = 2L * (oldBytes + newBytes) + 16L * 1024 * 1024
        check(android.os.StatFs(directory.toString()).availableBytes > reserve) { "Not enough free storage to stage and safely recover this import. No installed data or AI pairing was changed." }
    }

    /** Refuse close/replacement unless already-admitted transactions release the WAL. */
    internal fun awaitClosedCheckpoint(path: Path, timeoutMs: Long = 3000) {
        if (!Files.exists(path)) return
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        SQLiteDatabase.openDatabase(path.toString(), null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("PRAGMA busy_timeout=100", null).use { it.moveToFirst() }
            while (true) {
                val complete = try {
                    db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
                        check(c.moveToFirst() && c.columnCount == 3) { "Checkpoint did not return a verifiable result" }
                        c.getInt(0) == 0 && ((c.getInt(1) == 0 && c.getInt(2) == 0) || (c.getInt(1) == -1 && c.getInt(2) == -1))
                    }
                } catch (_: android.database.sqlite.SQLiteDatabaseLockedException) {
                    false
                }
                if (complete) return
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "Existing database operations are still active. Import was stopped before replacement; restart and retry." }
                Thread.sleep(25)
            }
        }
    }

    private fun structure(query: (String) -> Cursor): Map<String, List<String>> {
        val names = query("SELECT name,type FROM sqlite_master ORDER BY name").use { c ->
            buildList {
                while (c.moveToNext()) {
                    check(c.getString(1) in setOf("table", "index")) { "Unexpected executable schema in backup" }
                    if (c.getString(1) == "table" && c.getString(0) != "android_metadata" && !c.getString(0).startsWith("sqlite_")) add(c.getString(0))
                }
            }
        }
        fun rows(sql: String, skip: Set<Int> = emptySet()): List<String> = query(sql).use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).filter { it !in skip }.joinToString("|") { if (c.isNull(it)) "NULL" else c.getString(it).lowercase().replace("`", "").replace("\"", "") }) }.sorted()
        }
        return names.associateWith { name ->
            val escaped = name.replace("'", "''")
            columnStructure(name, query) + rows("PRAGMA foreign_key_list('$escaped')", setOf(0, 1)) +
                query("PRAGMA index_list('$escaped')").use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            val index = c.getString(1).replace("'", "''")
                            add(c.getInt(2).toString() + ":" + rows("PRAGMA index_info('$index')", setOf(1)).joinToString(","))
                        }
                    }.sorted()
                }
        }
    }
}
