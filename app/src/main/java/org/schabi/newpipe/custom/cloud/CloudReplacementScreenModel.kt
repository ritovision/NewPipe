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

/** Retains the exact approval and searchable diff across rotation, never in a saved-state Bundle. */
internal class CloudReplacementScreenModel(application: Application) : AndroidViewModel(application) {
    data class Screen(
        val busy: Boolean = false,
        val message: String = "Check cloud replacement and retained recovery copies.",
        val preview: String? = null,
        val pending: Boolean = false,
        val stale: Boolean = false,
        val canPrepare: Boolean = false,
        val loaded: Boolean = false
    )
    private val app = application.applicationContext
    private val controller = CloudReplacementController(app)
    private val mutable = MutableLiveData(Screen())
    val state: LiveData<Screen> = mutable
    private val rows = MutableLiveData<List<CloudScreenModel.Row>>(emptyList())
    val details: LiveData<List<CloudScreenModel.Row>> = rows
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var closed = false

    @Volatile private var preview: CloudReplacementController.Preview? = null

    @Volatile private var current: CloudReplacementController.Status? = null

    @Volatile private var groups: List<CloudChangeExplorer.Group> = emptyList()
    var query = ""
        private set
    private val expanded = HashSet<String>()
    private val counts = HashMap<String, Int>()
    private fun date(time: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
    private fun label(a: CloudRecoveryController.Archive) = "${if (a.kind == "device-zip") "Device ZIP" else "Cloud catalog"} · ${date(a.created)} · ${android.text.format.Formatter.formatShortFileSize(app, a.bytes.toLong())} · ${a.sourceDevice}\nArchive: ${a.id}"
    private fun publish(message: String) {
        if (closed) return
        val localPending = controller.hasPending()
        val pending = localPending || current?.pending == true
        val stale = if (localPending) runCatching { controller.pendingPhase() }.getOrNull() == "STALE" else current?.attempt?.getString("state") == "STALE"
        mutable.postValue(
            Screen(
                false,
                message,
                preview?.id,
                pending,
                stale,
                current?.allowed == true && (!pending || stale),
                true
            )
        )
        main.post { if (!closed) render() }
    }
    private fun run(message: String, action: () -> String) {
        if (mutable.value?.busy == true) return
        mutable.value = (mutable.value ?: Screen()).copy(busy = true, message = message)
        io.execute {
            try {
                publish(action())
            } catch (error: Exception) {
                publish(CloudUserMessages.error(error))
            } finally {
                CloudScheduler.reconcile(app)
            }
        }
    }
    fun inspect() = run("Checking replacement and retained copies…") {
        current = controller.inspect()
        val s = current!!
        val active = when (s.attempt?.getString("state")) {
            "ACTIVE" -> "An approved replacement is unfinished. Resume it or resolve it before another catalog action."
            "STALE" -> "The previous publication is resolved. Review the current cloud before retrying."
            else -> "Review the complete changes before replacing the cloud with this device's catalog. Reviewing, canceling, or completing replacement does not change your sync toggle. Unfinished replacements pause automatic work until resolved."
        }
        val cooldown = if (s.cooldownUntil > System.currentTimeMillis()) "\nNext replacement may be confirmed after ${date(s.cooldownUntil)}." else ""
        if (!s.allowed && !s.pending) {
            "Cloud replacement is not enabled for this connection."
        } else {
            "$active\n\n${s.archives.size} of 3 recovery slots occupied. The retained copies are listed below.$cooldown"
        }
    }
    fun prepare() = run("Freezing both catalogs and preparing the outgoing recovery copy…") {
        clearPreview()
        val p = controller.prepare()
        preview = p
        val hints = p.before.hints + p.outgoing.decoded.hints.associateBy { it.key }
        groups = CloudChangeExplorer.groups(p.outgoing.decoded.catalog, p.before.catalog, hints)
        val delta = CloudCatalogCodec.diff(p.outgoing.decoded.catalog, p.before.catalog)
        val rotation = p.evicted?.let { "This will permanently delete ${label(it)} before uploading the outgoing cloud backup." }
            ?: "No retained archive will be deleted."
        val kept = p.retained.joinToString("\n", transform = ::label).ifEmpty { "None yet" }
        "This device → cloud library\nReplacing cloud revision ${p.outgoing.status.head.revision}\n\n" +
            "${delta.added.size} videos added · ${delta.removed.size} removed\n${delta.playlists.size} playlists changed · ${delta.tags.added.size + delta.tags.removed.size + delta.tags.changed.size} tags changed\n\n" +
            "The exact outgoing cloud catalog is backed up and verified first. This device keeps its current contents. Other devices must reconcile this replacement.\n\n" +
            "$rotation\n\nExisting copies retained:\n$kept\n\nReview all changes below. Confirming publishes only this frozen catalog. Your sync toggle is unchanged."
    }
    fun confirm(id: String) = run("Verifying the outgoing backup and publishing the approved catalog…") {
        val p = checkNotNull(preview) { "CLOUD_PREVIEW_EXPIRED" }
        check(p.id == id) { "CLOUD_PREVIEW_EXPIRED" }
        try {
            current = controller.confirm(p)
        } finally {
            if (controller.hasPending()) {
                preview = null
                groups = emptyList()
            }
        }
        preview = null
        groups = emptyList()
        "Cloud replaced with the approved catalog. Its previous contents remain in the verified recovery copy. Your sync toggle is unchanged."
    }
    fun resume() = run("Resolving and resuming the same approved replacement…") {
        current = controller.resume()
        "Replacement resolved. Your sync toggle is unchanged."
    }
    fun resolveForRetry() = run("Resolving the old publication before a fresh preview…") {
        current = controller.resolveForRetry()
        if (current?.attempt?.getString("state") == "STALE") {
            "The previous publication is resolved. Its verified recovery copy is retained. Review the current cloud to retry using that same slot."
        } else {
            "Replacement completed and its receipt was resolved. Your sync toggle is unchanged."
        }
    }
    fun stop() = run("Resolving publication and stopping the attempt…") {
        current = controller.cancel()
        if (current?.pending == true) {
            "This request is resolved. An earlier replacement still needs attention. Stop it or review the current cloud to retry. Your sync toggle is unchanged."
        } else {
            "Attempt resolved. Completed recovery copies are retained. Your sync toggle is unchanged."
        }
    }
    fun cancelPreview() = run("Discarding the local preview…") {
        clearPreview()
        "Preview discarded. Your sync toggle is unchanged."
    }
    private fun clearPreview() {
        controller.cancelPreview()
        preview = null
        groups = emptyList()
        main.post {
            query = ""
            expanded.clear()
            counts.clear()
            render()
        }
    }
    fun search(value: String) {
        query = value
        render()
    }
    fun toggle(group: String) {
        if (!expanded.add(group)) expanded.remove(group)
        render()
    }
    fun more(group: String) {
        counts[group] = (counts[group] ?: 40) + 40
        render()
    }
    private fun render() {
        if (preview != null) {
            val result = ArrayList<CloudScreenModel.Row>()
            for (g in CloudChangeExplorer.search(groups, query)) {
                val open = g.id in expanded || query.isNotBlank()
                result.add(CloudScreenModel.Row(g.id, "${g.title} (${g.items.size})", "", if (open) "collapse" else "expand"))
                if (open) {
                    val limit = counts[g.id] ?: 40
                    g.items.take(limit).forEach { result.add(CloudScreenModel.Row(g.id, it.title, it.detail, "", it.kind)) }
                    if (g.items.size > limit) result.add(CloudScreenModel.Row(g.id, "Show more", "${g.items.size - limit} remaining", "more"))
                }
            }
            rows.value = result
        } else {
            rows.value = current?.archives.orEmpty().map {
                CloudScreenModel.Row(it.id, label(it), if (it.usable) "Verified retained recovery copy" else it.error ?: "Recovery copy requires attention", "")
            }
        }
    }
    override fun onCleared() {
        closed = true
        io.execute { runCatching { controller.cancelPreview() } }
        io.shutdown()
    }
}
