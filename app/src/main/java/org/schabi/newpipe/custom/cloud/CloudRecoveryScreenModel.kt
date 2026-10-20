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
import org.schabi.newpipe.custom.tagging.TagRuntime

/** The retained screen owns exact candidates; no files, credentials or catalogs enter saved state. */
internal class CloudRecoveryScreenModel(application: Application) : AndroidViewModel(application) {
    data class Screen(
        val busy: Boolean = false,
        val message: String = "Check cloud recovery to load the retained copies.",
        val preview: String? = null,
        val confirm: String = "Confirm",
        val nativeRestore: Boolean = false,
        val pending: Boolean = false,
        val loaded: Boolean = false
    )
    private val app = application.applicationContext
    private val controller = CloudRecoveryController(app)
    private val mutable = MutableLiveData(Screen())
    val state: LiveData<Screen> = mutable
    private val rows = MutableLiveData<List<CloudScreenModel.Row>>(emptyList())
    val details: LiveData<List<CloudScreenModel.Row>> = rows
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var closed = false

    @Volatile private var upload: CloudRecoveryController.UploadPreview? = null

    @Volatile private var restore: CloudRecoveryController.RestorePreview? = null

    @Volatile private var current: CloudRecoveryController.Status? = null

    @Volatile private var groups: List<CloudChangeExplorer.Group> = emptyList()
    private var query = ""
    private val expanded = HashSet<String>()
    private val counts = HashMap<String, Int>()
    private fun date(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
    private fun name(kind: String): String = if (kind == "device-zip") "Device ZIP" else "Cloud catalog"
    private fun label(a: CloudRecoveryController.Archive): String = "${name(a.kind)} · ${date(a.created)} · ${android.text.format.Formatter.formatShortFileSize(app, a.bytes.toLong())}"
    private fun publish(message: String) {
        if (closed) return
        val u = upload
        val r = restore
        mutable.postValue(
            Screen(
                false,
                message,
                u?.id ?: r?.id,
                if (u != null) {
                    "Upload this backup"
                } else if (r?.decoded != null) {
                    "Restore on this device"
                } else {
                    "Restore and restart"
                },
                r != null && r.decoded == null,
                controller.hasPending() || current?.operation != null,
                current != null
            )
        )
        main.post { if (!closed) render() }
    }
    private fun run(message: String, work: () -> String) {
        if (mutable.value?.busy == true) return
        mutable.value = (mutable.value ?: Screen()).copy(busy = true, message = message)
        io.execute {
            try {
                publish(work())
            } catch (e: Exception) {
                publish(CloudUserMessages.error(e))
            } finally {
                if (TagRuntime.restoring) main.post { com.jakewharton.processphoenix.ProcessPhoenix.triggerRebirth(app) }
            }
        }
    }
    fun inspect() = run("Checking retained recovery copies…") {
        current = controller.inspect()
        "${current!!.occupied} of 3 slots occupied. An unfinished upload also occupies a slot. Backups are created only when you request them."
    }
    fun prepare(kind: String) = run("Building and validating the exact backup…") {
        clearPreview()
        val p = controller.prepareUpload(kind)
        upload = p
        val rotation = p.evicted?.let { "This will permanently delete ${label(it)} from ${it.sourceDevice} before uploading." }
            ?: "No existing archive will be deleted."
        val retained = p.retained.joinToString("\n") { label(it) }.ifEmpty { "None yet" }
        "${name(p.manifest.kind)} · ${android.text.format.Formatter.formatShortFileSize(app, p.manifest.bytes.toLong())}\n\n$rotation\n\nCopies retained:\n$retained\n\nConfirm this exact backup and rotation."
    }
    fun prepareRestore(id: String) = run("Downloading and validating the archive. Automatic sync is being turned off…") {
        clearPreview()
        val p = controller.prepareRestore(id)
        restore = p
        if (p.decoded != null) {
            val hints = p.before.hints + p.decoded.hints.associateBy { it.key }
            groups = CloudChangeExplorer.groups(p.before.catalog, p.decoded.catalog, hints, p.before.orphaned)
            val d = CloudCatalogCodec.diff(p.before.catalog, p.decoded.catalog)
            "${label(p.archive)}\nFrom ${p.archive.sourceDevice}\n\n${d.added.size} videos added · ${d.removed.size} removed\n${d.playlists.size} playlists changed\n\nReview all changes below. This restores only this device's catalog; settings, history and subscriptions stay. A verified local safety copy is saved first. Automatic sync stays off."
        } else {
            "${label(p.archive)}\nFrom ${p.archive.sourceDevice}\n\nThe complete library, history, subscriptions and tags on this device will be replaced. A verified local safety copy is saved first. The app restarts, AI control is unpaired, and automatic sync stays off. Settings are kept unless you select the option below."
        }
    }
    fun confirm(id: String, settings: Boolean) = run("Applying the confirmed recovery action…") {
        upload?.let {
            check(it.id == id) { "CLOUD_PREVIEW_EXPIRED" }
            try {
                current = controller.confirmUpload(it)
            } finally {
                if (controller.hasPending()) upload = null
            }
            upload = null
            return@run "Backup uploaded and its stored bytes verified. ${current!!.occupied} of 3 slots occupied."
        }
        val p = checkNotNull(restore) { "CLOUD_PREVIEW_EXPIRED" }
        check(p.id == id)
        try {
            controller.confirmRestore(p, settings)
        } finally {
            p.close()
            restore = null
            groups = emptyList()
        }
        "Recovery catalog restored on this device. Automatic sync remains off."
    }
    fun resume() = run("Resuming the same reserved backup…") {
        current = controller.resume()
        "Recovery upload resolved. ${current!!.occupied} of 3 slots occupied."
    }
    fun abort() = run("Stopping the unfinished upload and confirming cleanup…") {
        current = controller.abort()
        "Upload resolved. ${current!!.occupied} of 3 slots occupied."
    }
    fun cancel() = run("Discarding the local preview…") {
        clearPreview()
        "Preview discarded. Automatic sync keeps its current setting."
    }
    private fun clearPreview() {
        if (upload != null) controller.cancelUploadPreview()
        upload = null
        restore?.close()
        restore = null
        groups = emptyList()
        main.post {
            expanded.clear()
            counts.clear()
            query = ""
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
        if (restore?.decoded != null) {
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
        } else if (upload == null && restore == null) {
            rows.value = current?.archives.orEmpty().map {
                CloudScreenModel.Row(
                    it.id,
                    label(it),
                    "From ${it.sourceDevice}\n${if (it.usable) "Verified stored copy · Tap to review restore" else it.error ?: it.state}",
                    if (it.usable) "restore" else ""
                )
            }
        } else {
            rows.value = emptyList()
        }
    }
    override fun onCleared() {
        closed = true
        io.execute { runCatching { clearPreview() } }
        io.shutdown()
    }
}
