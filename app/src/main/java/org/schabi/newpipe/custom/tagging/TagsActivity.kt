/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.ServiceHelper
import org.schabi.newpipe.util.ThemeHelper
import org.schabi.newpipe.util.image.CoilHelper

/** Real native Library browser. No fixtures, preview actions, local resets or AI dependency. */
class TagsActivity : AppCompatActivity() {
    private lateinit var ui: TagUi
    private lateinit var model: TagBrowserModel
    private lateinit var filterGroups: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private lateinit var count: TextView
    private lateinit var all: TextView
    private lateinit var untagged: TextView
    private lateinit var operator: TextView
    private lateinit var scroll: ScrollView
    private val actionButtons = ArrayList<TextView>()
    private val disposables = CompositeDisposable()
    private var shown = TagBrowserModel.State()
    private var dispatching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.setDayNightMode(this)
        ThemeHelper.setTheme(this, ServiceHelper.getSelectedServiceId(this))
        super.onCreate(savedInstanceState)
        ui = TagUi(this)
        model = ViewModelProvider(this)[TagBrowserModel::class.java]
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildScreen()
        model.action.observe(this) { action ->
            dispatching = action.busy
            updateActions()
            action.message?.let { status.text = it }
        }
        model.state.observe(this) { state ->
            shown = state
            render(state)
        }
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }

    private fun buildScreen() {
        val root = ui.column().apply { setBackgroundColor(ui.background) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        val header = ui.row().apply { setPadding(ui.dp(10), ui.dp(6), ui.dp(12), ui.dp(6)) }
        header.addView(
            ui.button("‹") { finish() }.apply {
                textSize = 27f
                contentDescription = "Back to NewPipe"
            },
            ui.params(44, -2)
        )
        header.addView(ui.text("Tags", 21f, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(
            ui.button("Manage") {
                TagManagerFragment.show(supportFragmentManager, model.filter.included.toLongArray(), model.filter.excluded.toLongArray())
            },
            ui.params(-2, -2)
        )
        root.addView(header, ui.params())
        scroll = ScrollView(this)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val content = ui.column().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(16)) }
        scroll.addView(content)
        status = ui.text("Loading your Library…", 12f, color = ui.secondary).apply {
            setPadding(0, ui.dp(5), 0, ui.dp(8))
            setOnClickListener { model.refresh() }
        }
        content.addView(status, ui.params())
        val search = ui.input("Search videos or uploaders").apply {
            setText(model.filter.query)
            background = ui.shape(ui.surface)
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
        }
        content.addView(search, ui.params())
        ui.space(content, 12)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                model.setFilter(model.filter.copy(query = s?.toString().orEmpty()))
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        val tabs = ui.row()
        all = ui.button("All saved videos") { model.setFilter(model.filter.copy(untagged = false)) }
        untagged = ui.button("Untagged") {
            model.setFilter(model.filter.copy(untagged = true, included = emptySet(), excluded = emptySet()))
        }
        tabs.addView(all, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ui.dp(8) })
        tabs.addView(untagged, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(tabs, ui.params())
        ui.space(content, 12)
        val filters = ui.row()
        filters.addView(ui.text("TAG FILTERS", 12f, true, ui.secondary), LinearLayout.LayoutParams(0, -2, 1f))
        operator = ui.button(if (model.filter.matchAll) "AND" else "OR") {
            model.setFilter(model.filter.copy(matchAll = !model.filter.matchAll))
        }
        filters.addView(operator, ui.params(72, -2))
        content.addView(filters, ui.params())
        content.addView(ui.text("Tap tags: include (+), exclude (−), clear. AND/OR combines included tags.", 11f, color = ui.secondary), ui.params())
        val expand = ui.row()
        expand.addView(ui.button("Expand all") { expandAll(true) }, LinearLayout.LayoutParams(0, -2, 1f))
        expand.addView(ui.button("Collapse all") { expandAll(false) }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(expand, ui.params())
        ui.space(content)
        filterGroups = ui.column()
        content.addView(filterGroups, ui.params())
        val resultHeader = ui.row()
        count = ui.text("Library", 18f, true)
        resultHeader.addView(count, LinearLayout.LayoutParams(0, -2, 1f))
        val sorting = Spinner(this).apply {
            adapter = ui.spinnerAdapter(listOf("Library order", "Title A–Z", "Title Z–A"))
            setSelection(model.filter.sort.ordinal)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val order = TagSort.entries[position]
                    if (model.filter.sort != order) model.setFilter(model.filter.copy(sort = order))
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        resultHeader.addView(sorting, ui.params(134, -2))
        content.addView(resultHeader, ui.params())
        ui.space(content)
        results = ui.column()
        content.addView(results, ui.params())
        val actions = ui.row().apply {
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
            setBackgroundColor(ui.surface)
        }
        listOf("Play results", "Add to queue", "Save playlist").forEachIndexed { index, title ->
            val button = ui.button(title, index == 0) { if (index == 2) playlistDialog() else dispatchPlayback(index == 1) }
            button.textSize = 12f
            actions.addView(
                button,
                LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginStart = ui.dp(3)
                    marginEnd = ui.dp(3)
                }
            )
            actionButtons.add(button)
        }
        root.addView(actions, ui.params())
    }

    private fun render(state: TagBrowserModel.State) {
        val previous = scroll.scrollY
        val snapshot = state.snapshot
        status.text = when {
            state.error != null -> "${state.error} Tap here to retry."
            state.busy -> "Updating Library…"
            snapshot == null -> "No Library loaded. Tap to retry."
            snapshot.orphanedAssignments > 0 -> "${snapshot.items.size} saved videos · ${snapshot.orphanedAssignments} tag links belong to absent videos; retained for restore."
            else -> "${snapshot.items.size} saved videos · Changes are stored on this device"
        }
        status.setTextColor(if (state.error == null) ui.secondary else ui.accent)
        all.background = ui.shape(if (!model.filter.untagged) ui.primary else ui.surface)
        all.setTextColor(if (!model.filter.untagged) android.graphics.Color.WHITE else ui.foreground)
        untagged.background = ui.shape(if (model.filter.untagged) ui.primary else ui.surface)
        untagged.setTextColor(if (model.filter.untagged) android.graphics.Color.WHITE else ui.foreground)
        operator.text = if (model.filter.matchAll) "AND" else "OR"
        operator.isEnabled = !model.filter.untagged
        renderGroups()
        count.text = "${state.matching.size} videos"
        renderResults()
        updateActions()
        scroll.post { scroll.scrollTo(0, previous) }
    }

    private fun updateActions() {
        val enabled = !shown.busy && shown.error == null && shown.matching.isNotEmpty() && !dispatching
        actionButtons.forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.45f
        }
    }

    private fun isExpanded(id: Long): Boolean = model.expandedGroups[id]
        ?: (id == shown.snapshot?.definitions?.categories?.firstOrNull()?.id)

    private fun expandAll(expanded: Boolean) {
        shown.snapshot?.definitions?.categories?.forEach { model.expandedGroups[it.id] = expanded }
        model.expandedGroups[0] = expanded
        renderGroups()
    }

    private fun renderGroups() {
        filterGroups.removeAllViews()
        val definitions = shown.snapshot?.definitions ?: return
        if (model.filter.untagged) {
            filterGroups.addView(ui.text("Saved videos with no tag assignments. Other tag filters are cleared.", 13f, color = ui.secondary), ui.params())
            return
        }
        for (category in definitions.categories) addCategory(category.id, category.name, definitions.tags.filter { it.categoryId == category.id })
        addCategory(0, "Uncategorized", definitions.tags.filter { it.categoryId == null })
        if (definitions.tags.isEmpty()) filterGroups.addView(ui.text("No tags yet. Open Manage to create some, or tag a video from its details page.", 13f, color = ui.secondary), ui.params())
    }

    private fun addCategory(id: Long, name: String, tags: List<TagDefinition>) {
        if (tags.isEmpty()) return
        val expanded = isExpanded(id)
        val active = tags.filter { it.id in model.filter.included || it.id in model.filter.excluded }
        val heading = ui.button("${if (expanded) "▾" else "▸"} $name · ${tags.size} tags${if (active.isEmpty()) "" else " · ${active.size} active"}") {
            model.expandedGroups[id] = !expanded
            renderGroups()
        }.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            contentDescription = "$name, ${if (expanded) "expanded" else "collapsed"}, ${active.size} active filters. Tap to toggle."
        }
        filterGroups.addView(heading, ui.params())
        val visible = if (expanded) tags else active
        if (visible.isNotEmpty()) {
            val flow = TagFlowLayout(this)
            for (tag in visible) {
                val positive = tag.id in model.filter.included
                val negative = tag.id in model.filter.excluded
                val tint = if (positive) {
                    ui.accent
                } else if (negative) {
                    ui.exclude
                } else {
                    ui.border
                }
                val fill = if (positive || negative) ColorUtils.blendARGB(ui.surface, tint, .18f) else ui.surface
                val chip = ui.button(
                    (
                        if (positive) {
                            "+ "
                        } else if (negative) {
                            "− "
                        } else {
                            ""
                        }
                        ) + tag.name
                ) {
                    val include = model.filter.included.toMutableSet()
                    val exclude = model.filter.excluded.toMutableSet()
                    when {
                        positive -> {
                            include.remove(tag.id)
                            exclude.add(tag.id)
                        }

                        negative -> exclude.remove(tag.id)

                        else -> include.add(tag.id)
                    }
                    model.setFilter(model.filter.copy(included = include, excluded = exclude))
                }.apply {
                    background = ui.shape(fill, 22, tint)
                    contentDescription = "${tag.name}, ${if (positive) {
                        "included"
                    } else if (negative) {
                        "excluded"
                    } else {
                        "unselected"
                    }}. Tap to cycle."
                }
                flow.addView(
                    chip,
                    android.view.ViewGroup.MarginLayoutParams(-2, -2).apply {
                        rightMargin = ui.dp(7)
                        topMargin = ui.dp(7)
                    }
                )
            }
            filterGroups.addView(flow, ui.params())
        }
        ui.space(filterGroups)
    }

    private fun renderResults() {
        results.removeAllViews()
        val rows = shown.matching
        val definitions = shown.snapshot?.definitions?.tags?.associate { it.id to it.name }.orEmpty()
        if (rows.isEmpty()) {
            val text = when {
                shown.busy -> "Loading…"
                shown.snapshot?.items?.isEmpty() == true -> "Your Library is empty. Save a video using Add to or Tags on its details page."
                model.filter.untagged -> "No untagged videos match your search."
                else -> "No matching videos. Try changing the filters or search."
            }
            results.addView(ui.text(text, 14f, color = ui.secondary).apply { setPadding(0, ui.dp(20), 0, ui.dp(20)) }, ui.params())
            return
        }
        // Bound the number of native rows, but Play/Queue/Save always use the entire result snapshot.
        val pageSize = 50
        val page = model.page.coerceIn(0, (rows.size - 1) / pageSize)
        model.page = page
        for (item in rows.drop(page * pageSize).take(pageSize)) {
            val row = ui.row().apply {
                setPadding(ui.dp(10), ui.dp(10), ui.dp(6), ui.dp(10))
                background = ui.shape(ui.surface)
            }
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = null
            }
            row.addView(image, ui.params(72, 48))
            CoilHelper.loadThumbnail(image, item.stream.thumbnailUrl)
            val details = ui.column().apply { setPadding(ui.dp(10), 0, ui.dp(6), 0) }
            details.addView(ui.text(item.stream.title, 14f, true), ui.params())
            details.addView(ui.text(item.stream.uploader, 12f, color = ui.secondary), ui.params())
            details.addView(ui.text(item.tagIds.mapNotNull { definitions[it] }.joinToString(" · ").ifEmpty { "Untagged" }, 11f, color = ui.secondary), ui.params())
            details.setOnClickListener {
                NavigationHelper.openVideoDetail(this, item.stream.serviceId, item.stream.url, item.stream.title, null, false)
            }
            details.isFocusable = true
            details.contentDescription = "Open ${item.stream.title}"
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(
                ui.button("Edit") { TagEditorFragment.show(supportFragmentManager, item.stream) }.apply {
                    contentDescription = "Edit tags for ${item.stream.title}"
                },
                ui.params(54, -2)
            )
            results.addView(row, ui.params())
            ui.space(results)
        }
        if (rows.size > pageSize) {
            results.addView(ui.text("Showing ${page * pageSize + 1}–${minOf((page + 1) * pageSize, rows.size)} of ${rows.size}", 12f, color = ui.secondary), ui.params())
            val pager = ui.row()
            pager.addView(
                ui.button("Previous 50") {
                    model.page--
                    renderResults()
                }.apply { isEnabled = page > 0 },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            pager.addView(
                ui.button("Next 50") {
                    model.page++
                    renderResults()
                }.apply { isEnabled = (page + 1) * pageSize < rows.size },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            results.addView(pager, ui.params())
        }
    }

    private fun capturedKeys() = shown.matching.map { StreamKey(it.stream.serviceId, it.stream.url) }

    private fun dispatchPlayback(append: Boolean) {
        if (shown.busy || shown.error != null || shown.matching.isEmpty() || dispatching) return
        model.play(capturedKeys(), append)
    }

    private fun playlistDialog() {
        if (shown.busy || shown.error != null || shown.matching.isEmpty() || dispatching) return
        val keys = capturedKeys()
        val name = ui.input("Playlist name").apply { setText(model.playlistName) }
        val body = ui.column().apply {
            setPadding(ui.dp(20), 0, ui.dp(20), 0)
            addView(ui.text("Save a static snapshot of ${keys.size} videos in this order.", 13f, color = ui.secondary), ui.params())
            addView(name, ui.params())
        }
        val dialog = ui.dialog().setTitle("Save as playlist").setView(body)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = name.text.toString().trim()
                if (title.isEmpty() || title.length > 150) {
                    name.error = "Use a name between 1 and 150 characters"
                    return@setOnClickListener
                }
                model.playlistName = title
                model.savePlaylist(title, keys)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    override fun onDestroy() {
        disposables.dispose()
        super.onDestroy()
    }
}
