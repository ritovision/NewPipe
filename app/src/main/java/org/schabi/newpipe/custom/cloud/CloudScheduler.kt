/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.content.Context
import androidx.room.InvalidationTracker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.reactivex.rxjava3.disposables.Disposable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.schabi.newpipe.custom.tagging.TagRuntime
import org.schabi.newpipe.database.AppDatabase

/** Persistent periodic + debounced foreground/native change work; off means no network admission. */
object CloudScheduler {
    private const val PERIODIC = "ritovision-cloud-periodic-v1"
    private const val IMMEDIATE = "ritovision-cloud-change-v1"
    private val installed = AtomicBoolean()
    private val generation = AtomicLong()
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "NewPipe-cloud-scheduling").apply { isDaemon = true } }
    private var subscription: Disposable? = null
    private val observers = ConcurrentHashMap<AppDatabase, InvalidationTracker.Observer>()

    @JvmStatic fun install(context: Context) {
        val app = context.applicationContext
        if (installed.compareAndSet(false, true)) {
            subscription = TagRuntime.observeChanges().subscribe({ request(app) }, { /* no network or credentials in observer errors */ })
        }
        reconcile(app)
    }

    @JvmStatic fun reconcile(context: Context) {
        val app = context.applicationContext
        executor.execute {
            try {
                val state = CloudConnectionStore(app).read()
                val work = WorkManager.getInstance(app)
                if (state?.enabled != true) {
                    work.cancelUniqueWork(PERIODIC)
                    work.cancelUniqueWork(IMMEDIATE)
                    observers.entries.toList().forEach { (db, observer) ->
                        db.invalidationTracker.removeObserver(observer)
                        observers.remove(db)
                    }
                    return@execute
                }
                val db = TagRuntime.get(app).library
                if (!observers.containsKey(db)) {
                    val observer = object : InvalidationTracker.Observer("playlists", "playlist_stream_join", "streams") {
                        override fun onInvalidated(tables: Set<String>) {
                            request(app)
                        }
                    }
                    if (observers.putIfAbsent(db, observer) == null) db.invalidationTracker.addObserver(observer)
                }
                val periodic = PeriodicWorkRequestBuilder<CloudSyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
                work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
                enqueue(app)
            } catch (_: Exception) { /* configuration errors surface on the Cloud page, not via global crash handlers */ }
        }
    }

    @JvmStatic fun request(context: Context) {
        val app = context.applicationContext
        val ticket = generation.incrementAndGet()
        executor.schedule({ if (generation.get() == ticket) enqueue(app) }, 2, TimeUnit.SECONDS)
    }
    private fun enqueue(app: Context) {
        try {
            if (CloudConnectionStore(app).read()?.enabled != true) return
            val work = OneTimeWorkRequestBuilder<CloudSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(app).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP, work)
        } catch (_: Exception) { /* WorkManager may be unavailable in isolated native fixtures. */ }
    }
    internal fun ticket(): Long = generation.get()
    internal fun complete(context: Context, started: Long) {
        if (generation.get() != started) executor.schedule({ enqueue(context.applicationContext) }, 2, TimeUnit.SECONDS)
    }

    @JvmStatic fun beforeImport(context: Context) {
        CloudConnectionStore(context.applicationContext).disable("A native restore disabled sync. Review the cloud copy before re-enabling.")
        reconcile(context)
    }

    @JvmStatic fun detached(db: AppDatabase) {
        observers.remove(db)?.let { db.invalidationTracker.removeObserver(it) }
    }
}

class CloudSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val controller = CloudSyncController(applicationContext)
        val ticket = CloudScheduler.ticket()
        return try {
            if (isStopped || controller.connection.read()?.enabled != true) return Result.success()
            controller.syncNow()
            Result.success()
        } catch (error: Exception) {
            controller.recordFailure(error)
            if (error is CloudRemoteException && error.retryable || error.message == "CLOUD_OPERATION_BUSY") Result.retry() else Result.success()
        } finally {
            CloudScheduler.complete(applicationContext, ticket)
        }
    }
}
