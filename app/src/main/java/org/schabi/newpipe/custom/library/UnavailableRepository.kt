/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.library

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject
import java.util.concurrent.Callable
import kotlin.concurrent.withLock
import org.schabi.newpipe.custom.tagging.TagRuntime
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity

/**
 * A manually curated, protected *subset* of the canonical Library.
 * Memberships live in the existing NewPipe playlist tables. No network requests or extra DB.
 */
object UnavailableRepository {
    const val NAME = "Unavailable"
    private data class Session(val database: AppDatabase, val id: Long)

    @Volatile private var session: Session? = null
    private val identityChanges = BehaviorSubject.createDefault(Unit).toSerialized()

    private fun publishSession(value: Session?) {
        session = value
        identityChanges.onNext(Unit)
    }

    @JvmStatic
    fun observeId(db: AppDatabase): Observable<Long> = identityChanges.map { boundId(db) ?: -1L }.distinctUntilChanged()

    @JvmStatic
    fun isReservedName(name: String?): Boolean = name?.trim()?.equals(NAME, true) == true

    @JvmStatic
    fun canDesignate(db: AppDatabase, name: String?): Boolean = boundId(db) == null && isReservedName(name)

    @JvmStatic
    fun isDeletionProtected(db: AppDatabase, id: Long, name: String?): Boolean = isBound(db, id) || isReservedName(name)

    @JvmStatic
    fun boundId(db: AppDatabase): Long? = session?.takeIf { it.database === db }?.id

    @JvmStatic
    fun isBound(db: AppDatabase, id: Long): Boolean = boundId(db) == id

    /** UI protection is identity-based; an unbound same-name user playlist may be renamed. */
    @JvmStatic
    fun isUiProtected(db: AppDatabase, id: Long, name: String?): Boolean = isBound(db, id)

    @JvmStatic
    fun reset() {
        publishSession(null)
    }

    /** Must be called only after the canonical Library has been initialized. */
    fun ensure(db: AppDatabase, bindingStore: LibraryBindingStore): Long = TagRuntime.lock.withLock {
        TagRuntime.assertAvailable()
        boundId(db)?.let { return it }
        check(LibraryRepository.boundLibraryId(db) != null) { "Initialize Library first" }
        val binding = bindingStore.read() ?: error("Canonical Library binding is unavailable")
        val id = binding.unavailablePlaylistId?.also { existingId ->
            check(nameOf(db, existingId) == NAME) {
                "Unavailable playlist identity differs after restore. Repair the binding before editing."
            }
        } ?: run {
            val created = db.runInTransaction(
                Callable {
                    val named = queryIds(db, "SELECT uid FROM playlists WHERE LOWER(TRIM(name))=LOWER(?)", NAME)
                    check(named.isEmpty()) {
                        "Another playlist is already named Unavailable. Rename it before enabling the special playlist."
                    }
                    db.playlistDAO().insert(
                        PlaylistEntity(
                            name = NAME,
                            isThumbnailPermanent = false,
                            thumbnailStreamId = PlaylistEntity.DEFAULT_THUMBNAIL_ID,
                            displayIndex = -1
                        )
                    )
                }
            )
            // Never adopt a name-only playlist after a crash between creation and this binding write.
            bindingStore.write(binding.copy(unavailablePlaylistId = created))
            created
        }
        publishSession(Session(db, id))
        id
    }

    /**
     * Explicit user-approved recovery after a crash between playlist creation and
     * binding or after importing a legacy catalog with a same-name playlist.
     * Never auto-adopt by name; require an unambiguous, user-selected row.
     */
    @JvmStatic
    fun rebind(context: Context, selectedId: Long): Completable = Completable.fromAction {
        val appContext = context.applicationContext
        val repository = TagRuntime.get(appContext)
        val db = repository.library
        repository.access { libraryId ->
            check(selectedId > 0 && selectedId != libraryId) { "Invalid Unavailable selection" }
            check(boundId(db) == null || boundId(db) == selectedId) {
                "Another Unavailable playlist is already protected"
            }
            val name = nameOf(db, selectedId)
            check(name?.trim()?.equals(NAME, ignoreCase = true) == true) {
                "Only a playlist named Unavailable can be designated"
            }
            val candidates = queryIds(
                db,
                "SELECT uid FROM playlists WHERE LOWER(TRIM(name))=LOWER(?)",
                NAME
            )
            check(candidates == listOf(selectedId)) {
                "Multiple Unavailable-named playlists exist; rename the others before designating"
            }
            val store = repository.binding
            val binding = store.read() ?: error("Canonical Library binding is unavailable")
            check(binding.playlistId == libraryId) {
                "Library identity changed; reopen Bookmarked Playlists"
            }
            // Explicit adoption is the only path permitted to normalize name,
            // eliminate repeated occurrences and ensure canonical Library
            // membership. Do it atomically; never infer the identity from a name.
            db.runInTransaction {
                check(nameOf(db, selectedId)?.trim()?.equals(NAME, true) == true) {
                    "The selected Unavailable playlist changed"
                }
                if (nameOf(db, selectedId) != NAME) {
                    db.openHelper.writableDatabase.execSQL(
                        "UPDATE playlists SET name=? WHERE uid=?",
                        arrayOf<Any>(NAME, selectedId)
                    )
                }
                val currentIds = queryIds(
                    db,
                    "SELECT stream_id FROM playlist_stream_join WHERE playlist_id=? ORDER BY join_index",
                    selectedId
                )
                val unique = currentIds.distinct()
                if (unique.size != currentIds.size) {
                    db.playlistStreamDAO().deleteBatch(selectedId)
                    db.playlistStreamDAO().insertAll(
                        unique.mapIndexed { index, streamId ->
                            PlaylistStreamEntity(selectedId, streamId, index)
                        }
                    )
                }
                // Existing ordinary membership might predate canonical Library.
                // The user-approved designation must not create a dangling subset.
                LibraryRepository.appendDirectly(db, libraryId, unique)
                LibraryRepository.refreshAutomaticThumbnail(db, selectedId)
            }
            store.write(binding.copy(unavailablePlaylistId = selectedId))
            publishSession(Session(db, selectedId))
        }
    }.subscribeOn(Schedulers.io())

    private fun queryIds(db: AppDatabase, sql: String, vararg args: Any): List<Long> = db.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, args)).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
    }

    private fun nameOf(db: AppDatabase, id: Long): String? = db.openHelper.readableDatabase.query(
        SimpleSQLiteQuery("SELECT name FROM playlists WHERE uid=?", arrayOf(id))
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    @JvmStatic
    fun requireNotProtected(db: AppDatabase, playlistId: Long) {
        check(!isBound(db, playlistId)) { "The Unavailable playlist cannot be renamed or deleted" }
        // A stale or unbound session must never authorize removal of a name-only candidate.
        check(nameOf(db, playlistId)?.trim()?.equals(NAME, true) != true) {
            "The Unavailable playlist identity needs repair"
        }
    }

    fun ids(db: AppDatabase, playlistId: Long): List<Long> = queryIds(
        db,
        "SELECT DISTINCT stream_id FROM playlist_stream_join WHERE playlist_id=? ORDER BY stream_id",
        playlistId
    )

    class SelectionChangedException : IllegalStateException(
        "Unavailable changed. Review the updated list and confirm again."
    )

    data class Preview(val playlistId: Long, val items: List<Long>) {
        val count: Int get() = items.size
    }

    internal fun preview(db: AppDatabase, store: LibraryBindingStore): Preview {
        val id = ensure(db, store)
        return Preview(id, ids(db, id))
    }

    /** Rechecks the exact set inside the writer transaction before a destructive operation. */
    internal fun verify(db: AppDatabase, preview: Preview): Long {
        val current = boundId(db) ?: error("Unavailable is not initialized")
        if (preview.playlistId != current || preview.items != ids(db, current)) {
            throw SelectionChangedException()
        }
        return current
    }

    @JvmStatic
    fun isMarked(db: AppDatabase, streamId: Long): Boolean = contains(db, streamId)

    internal fun contains(db: AppDatabase, streamId: Long): Boolean {
        val id = boundId(db) ?: return false
        return queryIds(
            db,
            "SELECT 1 FROM playlist_stream_join WHERE playlist_id=? AND stream_id=? LIMIT 1",
            id,
            streamId
        ).isNotEmpty()
    }

    @JvmStatic
    fun inspect(context: Context, streamId: Long): Single<Boolean> = Single.fromCallable {
        val repository = TagRuntime.get(context)
        repository.access {
            ensure(repository.library, repository.binding)
            contains(repository.library, streamId)
        }
    }.subscribeOn(Schedulers.io())

    @JvmStatic
    fun setMarked(context: Context, streamId: Long, marked: Boolean): Completable {
        val repository = try {
            TagRuntime.get(context)
        } catch (error: Exception) {
            return Completable.error(error)
        }
        return Completable.fromAction {
            val db = repository.library
            repository.access { library ->
                val unavailable = ensure(db, repository.binding)
                db.runInTransaction {
                    val saved = queryIds(
                        db,
                        "SELECT 1 FROM playlist_stream_join WHERE playlist_id=? AND stream_id=? LIMIT 1",
                        library,
                        streamId
                    ).isNotEmpty()
                    check(saved) { "This video is no longer saved in Library" }
                    if (marked) {
                        if (!contains(db, streamId)) {
                            val next = LibraryRepository.nextJoinIndex(db, unavailable)
                            db.playlistStreamDAO().insertAll(
                                listOf(PlaylistStreamEntity(unavailable, streamId, next))
                            )
                        }
                    } else {
                        db.openHelper.writableDatabase.execSQL(
                            "DELETE FROM playlist_stream_join WHERE playlist_id=? AND stream_id=?",
                            arrayOf<Any>(unavailable, streamId)
                        )
                    }
                    LibraryRepository.refreshAutomaticThumbnail(db, unavailable)
                }
            }
        }.subscribeOn(Schedulers.io())
    }

    @JvmStatic
    fun getPreview(context: Context): Single<Preview> = Single.fromCallable {
        val repository = TagRuntime.get(context)
        repository.access { preview(repository.library, repository.binding) }
    }.subscribeOn(Schedulers.io())

    @JvmStatic
    fun clear(context: Context, expected: Preview): Completable = Completable.fromAction {
        val repository = TagRuntime.get(context)
        val db = repository.library
        repository.access {
            ensure(db, repository.binding)
            db.runInTransaction { clearInTransaction(db, expected) }
        }
    }.subscribeOn(Schedulers.io())

    internal fun clearInTransaction(db: AppDatabase, expected: Preview) {
        val id = verify(db, expected)
        db.playlistStreamDAO().deleteBatch(id)
        LibraryRepository.refreshAutomaticThumbnail(db, id)
    }

    @JvmStatic
    fun removeAll(context: Context, expected: Preview): Completable = Completable.fromAction {
        val repository = TagRuntime.get(context)
        repository.removeUnavailableEverywhere(expected)
    }.subscribeOn(Schedulers.io())
}
