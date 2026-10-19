/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Callable
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.custom.library.LibraryRepository
import org.schabi.newpipe.custom.library.UnavailableRepository
import org.schabi.newpipe.custom.tagging.StreamKey
import org.schabi.newpipe.custom.tagging.TagRepository
import org.schabi.newpipe.custom.tagging.TagRuntime
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType

/** Test-only file-backed native catalog, always isolated within the Cloud QA package. */
internal class CloudApplyFixture(app: Context, val dir: Path, val scope: CloudIdentityStore.Scope = CloudIdentityStore.Scope("apply-test", "library-test")) {
    val context: Context = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File = dir.toFile()
        override fun getNoBackupFilesDir(): File = dir.resolve("private-nobackup").toFile().also { it.mkdirs() }
        override fun getDatabasePath(name: String): File = if (name.startsWith("/")) File(name) else dir.resolve(name).toFile()
    }
    lateinit var db: AppDatabase
        private set
    lateinit var repo: TagRepository
        private set
    init {
        reopen()
    }
    fun reopen() {
        close()
        db = NewPipeDatabase.getInstance(context)
        repo = TagRuntime.get(context)
    }
    fun close() {
        NewPipeDatabase.close()
        TagRuntime.restoring = false
        LibraryRepository.forgetSessionForTest()
        UnavailableRepository.reset()
    }
    fun initialize() {
        repo.definitions()
    }
    fun capture(): CloudNativeSnapshot {
        val c = CloudIdentityStore.context(repo.store.readableDatabase)
        return if (c == null) CloudNativeCatalog(repo).enroll(scope) else CloudNativeCatalog(repo).capture(scope, c.incarnation)
    }
    fun media(name: String, tagIds: Set<Long> = emptySet()): StreamEntity {
        val item = StreamEntity(
            serviceId = 0,
            url = "https://example.test/$name",
            title = name,
            streamType = StreamType.VIDEO_STREAM,
            duration = 42,
            uploader = "Synthetic",
            viewCount = 123
        )
        val id = TagRuntime.mutate(
            db,
            Callable {
                db.runInTransaction(
                    Callable {
                        val uid = db.streamDAO().upsert(item)
                        LibraryRepository.appendDirectly(db, checkNotNull(LibraryRepository.boundLibraryId(db)), listOf(uid))
                        uid
                    }
                )
            }
        )
        val saved = item.copy(uid = id)
        if (tagIds.isNotEmpty()) repo.apply(saved, tagIds, repo.store.revision(StreamKey(0, saved.url)))
        return saved
    }
    fun playlist(name: String?, items: List<StreamEntity>, rank: Long = 0): Long = TagRuntime.mutate(
        db,
        Callable {
            db.runInTransaction(
                Callable {
                    val id = db.playlistDAO().insert(PlaylistEntity(name = name, isThumbnailPermanent = false, thumbnailStreamId = items.firstOrNull()?.uid ?: -1, displayIndex = rank))
                    db.playlistStreamDAO().insertAll(items.mapIndexed { i, s -> PlaylistStreamEntity(id, s.uid, i) })
                    id
                }
            )
        }
    )
    fun main(sql: String, vararg args: Any?) = TagRuntime.runMutation(db, Runnable { db.openHelper.writableDatabase.execSQL(sql, args) })
    fun rawMain(sql: String, vararg args: Any?) {
        db.openHelper.writableDatabase.execSQL(sql, args)
    }
    fun seed(): CloudNativeSnapshot {
        val category = repo.createCategory("Genre")
        val tag = repo.createTag("Keep", category)
        val a = media("A", setOf(tag))
        val b = media("B", setOf(tag))
        media("C")
        playlist("Existing", listOf(a, a, b), 3)
        playlist("Delete", listOf(b), 4)
        main("INSERT INTO playlist_stream_join VALUES(?,?,0)", UnavailableRepository.boundId(db)!!, b.uid)
        return capture()
    }
    fun target(before: CloudNativeSnapshot): CloudCatalog {
        val a = before.catalog.library.first()
        val d = CloudKey(0, "https://example.test/D")
        return before.catalog.copy(
            library = listOf(d, a),
            unavailable = listOf(d),
            playlists = listOf(
                before.catalog.playlists.first().copy(name = "Renamed", artwork = a, items = listOf(d, a, a)),
                CloudPlaylist("30000000-0000-0000-0000-000000000099", "New", null, listOf(a, d))
            ),
            categories = before.catalog.categories.map { it.copy(name = "Renamed category", nameKey = "renamed category") },
            tags = before.catalog.tags.map { it.copy(name = "Renamed tag", nameKey = "renamed tag") },
            assignments = listOf(CloudAssignment(d, listOf(before.catalog.tags.single().id)))
        )
    }
    fun head(target: CloudCatalog, revision: Long = 1) = CloudHead(scope.service, scope.library, "epoch-test", revision.toString(), revision.toString(), CloudCatalogCodec.fingerprint(target))
    fun hint(key: CloudKey) = CloudMediaHint(key, "Cloud D", "Remote uploader", "https://example.test/image.jpg", 120, "VIDEO_STREAM")
    fun scalar(sql: String): Long = db.openHelper.readableDatabase.query(sql).use { c ->
        check(c.moveToFirst())
        c.getLong(0)
    }
    fun sideScalar(sql: String): Long = repo.store.readableDatabase.rawQuery(sql, null).use { c ->
        check(c.moveToFirst())
        c.getLong(0)
    }
    fun mainRows(sql: String): List<String> = db.openHelper.readableDatabase.query(sql).use { c ->
        buildList { while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { if (c.isNull(it)) "<null>" else c.getString(it) }) }
    }
}
