/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Activity-scoped UI state survives rotation; no credential/large preview goes into a Bundle. */
internal class CloudScreenModel(application: Application) : AndroidViewModel(application) {
    data class Screen(
        val busy: Boolean = false,
        val connected: Boolean = false,
        val enabled: Boolean = false,
        val origin: String = "",
        val device: String = "",
        val message: String = "Not connected",
        val lastSync: String = "Never",
        val pending: Boolean = false,
        val hasLocalRecovery: Boolean = false,
        val previewId: String? = null,
        val previewSummary: String = "",
        val confirmLabel: String = "Confirm",
        val reconnectPending: Boolean = false
    )
    data class Row(val group: String, val title: String, val detail: String, val action: String, val kind: String = "")
    private val app = application.applicationContext
    private val controller = CloudSyncController(app)
    private val mutable = MutableLiveData(Screen())
    val state: LiveData<Screen> = mutable
    private val detailMutable = MutableLiveData<List<Row>>(emptyList())
    val details: LiveData<List<Row>> = detailMutable

    @Volatile private var preview: CloudSyncController.Preview? = null

    @Volatile private var groups: List<CloudChangeExplorer.Group> = emptyList()
    private val querySequence = AtomicLong()
    private val expanded = HashSet<String>()
    private val counts = HashMap<String, Int>()
    var query = ""
        private set

    @Volatile private var cleared = false
    init {
        refresh()
    }
    private fun screen(message: String? = null, busy: Boolean = false): Screen {
        val s = controller.connection.read()
        val p = preview
        return Screen(
            busy, s != null, s?.enabled == true, s?.origin.orEmpty(), s?.device.orEmpty(),
            message ?: s?.message ?: "Not connected", s?.lastSuccess?.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "Never",
            controller.hasPending(), java.io.File(controller.connection.directory, "recovery/before-last-cloud-restore.zip").isFile, p?.id, p?.let(::summary).orEmpty(),
            if (p?.direction == CloudSyncController.Direction.INITIALIZE_CLOUD) {
                "Upload and enable sync"
            } else if (p?.enableAfter == true) {
                "Use cloud and enable sync"
            } else {
                "Restore this cloud copy"
            },
            controller.connection.reconnectPending()
        )
    }
    private fun summary(p: CloudSyncController.Preview): String {
        val d = p.delta
        val direction = if (p.direction == CloudSyncController.Direction.INITIALIZE_CLOUD) "This device → empty cloud" else "Cloud → this device"
        val recovery = if (p.direction == CloudSyncController.Direction.TO_DEVICE) "A verified local recovery backup is saved before changes. It replaces the previous manual recovery copy. Settings, history and subscriptions stay on this device." else "The cloud has no catalog yet. This uploads the current local library without replacing this device."
        return "$direction\n\n${d.added.size} videos added · ${d.removed.size} videos removed\n${d.playlists.size} playlists changed · ${d.tags.added.size + d.tags.removed.size + d.tags.changed.size} tags changed\n\n$recovery\n\nThe exact local and cloud versions are checked again when you confirm."
    }
    private fun run(message: String, action: () -> String?) {
        if (mutable.value?.busy == true) return
        mutable.value = (mutable.value ?: Screen()).copy(busy = true, message = message)
        io.execute {
            try {
                val result = action()
                if (!cleared) mutable.postValue(screen(result))
            } catch (error: Exception) {
                controller.recordFailure(error)
                if (!cleared) mutable.postValue(screen(CloudUserMessages.error(error)))
            }
        }
    }
    fun refresh() {
        if (mutable.value?.busy == true) return
        io.execute {
            try {
                if (!cleared) mutable.postValue(screen())
            } catch (e: Exception) {
                if (!cleared) mutable.postValue(Screen(message = CloudUserMessages.error(e)))
            }
        }
    }
    fun connect(origin: String, token: String) = run("Checking the Worker and securing this device token…") {
        controller.connect(origin, token)
        null
    }
    fun reconnect(token: String) = run("Checking the recovered library and preserving pending work…") {
        controller.reconnect(token)
        preview = null
        null
    }
    fun resumeReconnect() = run("Finishing the saved reconnection…") {
        controller.resumeReconnect()
        preview = null
        null
    }
    fun inspect() = run("Checking cloud connection…") {
        val remote = controller.inspect()
        if (remote.initialized) "Connected. Cloud revision ${remote.head.revision}, ${remote.pages} data pages, ${remote.bytes} bytes." else "Connected. The cloud has no library yet."
    }
    fun prepare(enable: Boolean) = run("Comparing the exact local and cloud libraries…") {
        val p = controller.prepare(enable)
        val before = if (p.direction == CloudSyncController.Direction.INITIALIZE_CLOUD) CloudCatalog() else p.before.catalog
        val hints = p.before.hints + p.download?.decoded?.hints.orEmpty().associateBy { it.key }
        val computed = CloudChangeExplorer.groups(before, p.target, hints, if (p.direction == CloudSyncController.Direction.TO_DEVICE) p.before.orphaned else emptyList())
        preview = p
        groups = computed
        main.post {
            expanded.clear()
            counts.clear()
            query = ""
            renderDetails()
        }
        "Review changes before confirming."
    }
    fun confirm(id: String) = run("Applying the reviewed library with recovery protection…") {
        val p = preview
        check(p != null && p.id == id) { "CLOUD_PREVIEW_EXPIRED" }
        controller.confirm(p)
        preview = null
        groups = emptyList()
        CloudScheduler.reconcile(app)
        null
    }
    fun cancelPreview() {
        if (mutable.value?.busy == true) return
        preview = null
        groups = emptyList()
        querySequence.incrementAndGet()
        detailMutable.value = emptyList()
        mutable.value = (mutable.value ?: Screen()).copy(previewId = null, previewSummary = "")
    }
    fun disable() {
        preview = null
        groups = emptyList()
        querySequence.incrementAndGet()
        mutable.value = (mutable.value ?: Screen()).copy(enabled = false, previewId = null, message = "Turning off synchronization…")
        // Do not queue behind a blocked network operation. The shared native barrier is its linearization point.
        urgent.execute {
            try {
                controller.disable()
                CloudScheduler.reconcile(app)
                if (!cleared) mutable.postValue(screen(busy = mutable.value?.busy == true))
            } catch (e: Exception) {
                if (!cleared) mutable.postValue(screen(CloudUserMessages.error(e)))
            }
        }
    }
    fun syncNow() = run("Synchronizing library changes…") { controller.syncNow() }
    fun resolve() = run("Resolving the interrupted upload…") { controller.resolvePending() }
    fun forget() = run("Forgetting cloud credentials; local library is kept…") {
        controller.forget()
        CloudScheduler.reconcile(app)
        "Connection forgotten. Your local library is unchanged."
    }
    fun restoreLocalRecovery() = run("Validating and restoring the local safety backup…") {
        check(!CloudReplacementController.hasPending(controller.connection.directory)) { "CLOUD_REPLACEMENT_PENDING" }
        val file = java.io.File(controller.connection.directory, "recovery/before-last-cloud-restore.zip")
        check(file.isFile) { "LOCAL_RECOVERY_NOT_AVAILABLE" }
        val helper = org.schabi.newpipe.streams.io.StoredFileHelper(app, null, android.net.Uri.fromFile(file), "cloud-local-recovery")
        org.schabi.newpipe.custom.tagging.TagBackupCoordinator.prepare(app, helper).use {
            try {
                org.schabi.newpipe.custom.tagging.TagBackupCoordinator.install(app, it)
            } finally {
                if (org.schabi.newpipe.custom.tagging.TagRuntime.restoring) main.post { com.jakewharton.processphoenix.ProcessPhoenix.triggerRebirth(app) }
            }
        }
        "Local safety backup restored."
    }
    fun search(text: String) {
        query = text
        renderDetails()
    }
    fun toggle(group: String) {
        if (!expanded.add(group)) expanded.remove(group)
        renderDetails()
    }
    fun more(group: String) {
        counts[group] = (counts[group] ?: 100) + 100
        renderDetails()
    }
    private fun renderDetails() {
        val revision = querySequence.incrementAndGet()
        val snapshot = groups
        val filter = query
        val open = expanded.toSet()
        val limits = counts.toMap()
        cpu.execute {
            val found = CloudChangeExplorer.search(snapshot, filter)
            val rows = ArrayList<Row>()
            for (group in found) {
                val unfolded = filter.isNotBlank() || group.id in open
                rows.add(Row(group.id, group.title, "${group.items.size} changes", if (unfolded) "collapse" else "expand"))
                if (unfolded) {
                    val limit = limits[group.id] ?: 100
                    group.items.take(limit).forEach { rows.add(Row(group.id, it.title, it.detail, "item", it.kind)) }
                    if (group.items.size > limit) rows.add(Row(group.id, "Show 100 more", "${group.items.size - limit} more changes in this section", "more"))
                }
            }
            if (rows.isEmpty()) rows.add(Row("none", if (filter.isBlank()) "No semantic changes" else "No matching changes", "", "item"))
            if (!cleared && querySequence.get() == revision) detailMutable.postValue(rows)
        }
    }
    override fun onCleared() {
        cleared = true
        preview = null
        groups = emptyList()
        querySequence.incrementAndGet()
    }
    companion object {
        private val io = Executors.newSingleThreadExecutor { Thread(it, "NewPipe-cloud-screen").apply { isDaemon = true } }
        private val urgent = Executors.newSingleThreadExecutor { Thread(it, "NewPipe-cloud-disable").apply { isDaemon = true } }
        private val cpu = Executors.newSingleThreadExecutor { Thread(it, "NewPipe-cloud-diff").apply { isDaemon = true } }
        private val main = Handler(Looper.getMainLooper())
    }
}
