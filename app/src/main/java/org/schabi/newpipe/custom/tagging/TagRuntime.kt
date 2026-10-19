/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.content.Context
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.PublishSubject
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.custom.library.LibraryBindingStore
import org.schabi.newpipe.database.AppDatabase

/** One process-local write barrier; acquired BEFORE either database's writer transaction. */
object TagRuntime {
    internal val lock = ReentrantLock(true)
    private val repositories = ConcurrentHashMap<AppDatabase, TagRepository>()
    private val changes = PublishSubject.create<Unit>().toSerialized()

    @Volatile
    internal var restoring = false

    @Volatile internal var generation = 0L
        private set

    /** Registration only: constructing SQLiteOpenHelper performs no database I/O. */
    fun attach(context: Context, db: AppDatabase) {
        repositories.putIfAbsent(
            db,
            TagRepository(db, TagStore(context.applicationContext), LibraryBindingStore(context.applicationContext))
        )
    }

    @JvmStatic
    fun get(context: Context): TagRepository {
        val db = NewPipeDatabase.getInstance(context.applicationContext)
        return checkNotNull(repositories[db]) { "Tagging is unavailable for this database" }
    }

    internal fun registerForTest(db: AppDatabase, repository: TagRepository) {
        repositories[db] = repository
    }

    internal fun unregister(db: AppDatabase) {
        repositories.remove(db)
    }

    internal fun lookup(db: AppDatabase): TagRepository? = repositories[db]

    internal fun detach(db: AppDatabase) {
        org.schabi.newpipe.custom.cloud.CloudScheduler.detached(db)
        repositories.remove(db)?.let {
            it.invalidate()
            it.store.close()
        }
    }

    /** AI shutdown/drain must finish BEFORE entering this barrier. */
    internal fun freezeForRestore() {
        check(lock.isHeldByCurrentThread)
        check(!restoring) { "An import is already in progress. Restart the app." }
        restoring = true
        generation++
        repositories.values.forEach { it.invalidate() }
    }

    internal fun assertAvailable() {
        check(!restoring) { "A Library restore is in progress. Restart the app before making changes." }
    }

    @JvmStatic
    fun <T> mutate(db: AppDatabase, action: Callable<T>): T = lock.withLock {
        assertAvailable()
        repositories[db]?.beforeLibraryMutation()
        action.call()
    }

    @JvmStatic
    fun runMutation(db: AppDatabase, action: Runnable) {
        mutate(db, Callable { action.run() })
    }

    fun observeChanges(): Observable<Unit> = changes.hide()

    internal fun changed() = changes.onNext(Unit)
}
