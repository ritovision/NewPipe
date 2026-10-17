/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.custom.library.LibraryBindingStore
import org.schabi.newpipe.custom.library.LibraryRepository
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.testUtil.TestDatabase

/** Actual native views with synthetic stores, with screenshots for visual inspection. */
class TagUiAcceptanceTest {
    @Test fun nativeAccordionsEditorsAndManagementHaveReadableThemesAndRealState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val themeKey = context.getString(R.string.theme_key)
        val previous = preferences.getString(themeKey, null)
        val output = context.cacheDir.toPath().resolve("tag-ui-acceptance")
        Files.createDirectories(output)
        try {
            for ((name, value) in listOf("dark" to context.getString(R.string.dark_theme_key), "light" to context.getString(R.string.light_theme_key), "black" to context.getString(R.string.black_theme_key))) {
                preferences.edit().putString(themeKey, value).commit()
                TagRuntime.restoring = false
                NewPipeDatabase.close()
                LibraryRepository.forgetSessionForTest()
                val db = TestDatabase.createReplacingNewPipeDatabase()
                val bindingPath = context.cacheDir.toPath().resolve("ui-acceptance-${UUID.randomUUID()}.db")
                val store = TagStore(context, null)
                val repository = TagRepository(db, store, LibraryBindingStore(bindingPath))
                TagRuntime.registerForTest(db, repository)
                val genre = repository.createCategory("Genre")
                val mood = repository.createCategory("Mood")
                val jazz = repository.createTag("Jazz", genre)
                val rock = repository.createTag("Rock", genre)
                repository.createTag("Hip-Hop", genre)
                val chill = repository.createTag("Chill", mood)
                repository.createTag("Energetic", mood)
                repository.createTag("Favorites", null)
                val stream = StreamEntity(serviceId = 0, url = "https://example.org/ui-jazz", title = "Evening Jazz Session", streamType = StreamType.VIDEO_STREAM, duration = 180, uploader = "Synthetic Quartet")
                repository.apply(stream, setOf(jazz, chill), 0)
                val rockStream = stream.copy(url = "https://example.org/ui-rock", title = "Live at the Studio", uploader = "Synthetic Band")
                repository.apply(rockStream, setOf(rock), 0)
                val untagged = stream.copy(url = "https://example.org/ui-untagged", title = "Untitled Recording", uploader = "Synthetic Archive")
                repository.apply(untagged, setOf(jazz), 0)
                repository.apply(untagged, emptySet(), repository.editor(StreamKey(0, untagged.url)).revision)
                val scenario = ActivityScenario.launch(TagsActivity::class.java)
                try {
                    waitFor(scenario) { ViewModelProvider(it)[TagBrowserModel::class.java].state.value?.let { state -> !state.busy && state.matching.size == 3 } == true }
                    scenario.onActivity { activity ->
                        assertContrast(find(activity.window.decorView, "All saved videos"))
                        assertContrast(find(activity.window.decorView, "Play results"))
                        find(activity.window.decorView, "Jazz").performClick()
                    }
                    waitFor(scenario) { ViewModelProvider(it)[TagBrowserModel::class.java].state.value?.let { state -> !state.busy && state.matching.size == 1 } == true }
                    scenario.onActivity { activity ->
                        find(activity.window.decorView, "Rock").performClick()
                    }
                    waitFor(scenario) { findOrNull(it.window.decorView, "+ Rock") != null }
                    scenario.onActivity { activity -> find(activity.window.decorView, "+ Rock").performClick() }
                    waitFor(scenario) { findOrNull(it.window.decorView, "− Rock") != null }
                    scenario.onActivity { activity ->
                        find(activity.window.decorView, "Collapse all").performClick()
                        assertTrue(find(activity.window.decorView, "+ Jazz").isShown)
                        assertTrue(find(activity.window.decorView, "− Rock").isShown)
                        assertEquals(null, findOrNull(activity.window.decorView, "Hip-Hop"))
                    }
                    screenshot(output.resolve("$name-accordions.png"))
                    scenario.onActivity { activity -> find(activity.window.decorView, "Manage").performClick() }
                    waitFor(scenario) { activity ->
                        (activity.supportFragmentManager.findFragmentByTag("rv-tag-manager") as? TagManagerFragment)?.dialog?.window?.decorView?.let { findOrNull(it, "Manage tags & categories") != null } == true
                    }
                    scenario.onActivity { activity ->
                        val dialog = (activity.supportFragmentManager.findFragmentByTag("rv-tag-manager") as TagManagerFragment).dialog!!
                        assertContrast(find(dialog.window!!.decorView, "+ Tag"))
                        assertContrast(find(dialog.window!!.decorView, "Done"))
                    }
                    screenshot(output.resolve("$name-manager.png"))
                    scenario.onActivity { activity -> (activity.supportFragmentManager.findFragmentByTag("rv-tag-manager") as TagManagerFragment).dismiss() }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    scenario.onActivity { activity -> TagEditorFragment.show(activity.supportFragmentManager, stream) }
                    waitFor(scenario) { activity ->
                        val dialog = (activity.supportFragmentManager.findFragmentByTag("rv-persistent-tag-editor") as? TagEditorFragment)?.dialog
                        dialog?.window?.decorView?.let { findOrNull(it, "Apply")?.isEnabled == true } == true
                    }
                    scenario.onActivity { activity ->
                        val dialog = (activity.supportFragmentManager.findFragmentByTag("rv-persistent-tag-editor") as TagEditorFragment).dialog!!
                        val root = dialog.window!!.decorView
                        assertContrast(find(root, "Apply"))
                        val jazzCheck = descendants(root).filterIsInstance<CheckBox>().single { it.text.startsWith("Jazz") }
                        assertTrue(jazzCheck.isChecked)
                        jazzCheck.performClick()
                    }
                    screenshot(output.resolve("$name-editor.png"))
                    scenario.onActivity { activity ->
                        val dialog = (activity.supportFragmentManager.findFragmentByTag("rv-persistent-tag-editor") as TagEditorFragment).dialog!!
                        find(dialog.window!!.decorView, "Cancel").performClick()
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    assertEquals(setOf(jazz, chill), repository.editor(StreamKey(0, stream.url)).assigned)
                    println("NATIVE_TAG_UI_THEME_VERIFIED=$name")
                } finally {
                    scenario.close()
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    TagRuntime.unregister(db)
                    store.close()
                    db.close()
                    LibraryRepository.forgetSessionForTest()
                    LibraryBindingStore.deleteForDatabase(bindingPath)
                }
            }
        } finally {
            if (previous == null) preferences.edit().remove(themeKey).commit() else preferences.edit().putString(themeKey, previous).commit()
            TagRuntime.restoring = false
        }
    }

    private fun screenshot(path: java.nio.file.Path) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(250)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        check(image != null)
        Files.newOutputStream(path).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun findOrNull(root: View, value: String): TextView? = descendants(root).filterIsInstance<TextView>().firstOrNull { it.text.toString() == value }
    private fun find(root: View, value: String): TextView = checkNotNull(findOrNull(root, value)) { "Missing native control: $value" }
    private fun assertContrast(view: TextView) {
        val fill = when (val drawable = view.background) {
            is GradientDrawable -> drawable.color?.defaultColor
            is ColorDrawable -> drawable.color
            else -> null
        }
        assertTrue("Expected explicitly themed native button", fill != null)
        assertTrue("Illegible foreground/background for ${view.text}", androidx.core.graphics.ColorUtils.calculateContrast(view.currentTextColor, fill!!) >= 4.5)
        assertFalse(view.currentTextColor == Color.WHITE && fill == Color.WHITE)
    }
    private fun waitFor(scenario: ActivityScenario<TagsActivity>, condition: (TagsActivity) -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            var ready = false
            scenario.onActivity { ready = condition(it) }
            if (ready) return
            Thread.sleep(80)
        }
        error("Native UI did not reach expected state")
    }
}
