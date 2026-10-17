/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.custom.library.LibraryBindingStore
import org.schabi.newpipe.custom.library.LibraryRepository
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.testUtil.TestDatabase

class TagFormLifecycleTest {
    private lateinit var db: AppDatabase
    private lateinit var store: TagStore
    private lateinit var repository: TagRepository
    private lateinit var path: java.nio.file.Path
    private lateinit var stream: StreamEntity
    private lateinit var scenario: ActivityScenario<TagsActivity>
    private val release = CountDownLatch(1)
    private var writer: Thread? = null

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        TagRuntime.restoring = false
        NewPipeDatabase.close()
        LibraryRepository.forgetSessionForTest()
        db = TestDatabase.createReplacingNewPipeDatabase()
        path = app.cacheDir.toPath().resolve("form-lifecycle-${UUID.randomUUID()}.db")
        store = TagStore(app, null)
        repository = TagRepository(db, store, LibraryBindingStore(path))
        TagRuntime.registerForTest(db, repository)
        val tag = repository.createTag("Fixture", null)
        stream = StreamEntity(serviceId = 0, url = "https://example.org/form", title = "Synthetic form target", streamType = StreamType.VIDEO_STREAM, duration = 10, uploader = "Synthetic")
        repository.apply(stream, setOf(tag), 0)
        scenario = ActivityScenario.launch(TagsActivity::class.java)
        scenario.onActivity { TagEditorFragment.show(it.supportFragmentManager, stream) }
        waitFor { activity -> editor(activity)?.dialog?.window?.decorView?.let { find(it, "Apply")?.isEnabled } == true }
    }

    @After fun cleanup() {
        release.countDown()
        writer?.join(5000)
        scenario.close()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        TagRuntime.unregister(db)
        store.close()
        db.close()
        LibraryRepository.forgetSessionForTest()
        LibraryBindingStore.deleteForDatabase(path)
    }

    @Test fun definitionDraftAndDelayedCreationSurviveRotationAndSelectCreatedTagOnce() {
        scenario.onActivity { activity -> find(editor(activity)!!.dialog!!.window!!.decorView, "+ New tag")!!.performClick() }
        waitFor { form(it)?.dialog?.isShowing == true }
        scenario.onActivity { activity -> input(form(activity)!!).setText("Retained new tag") }
        scenario.recreate()
        waitFor { form(it)?.dialog?.isShowing == true }
        scenario.onActivity { activity -> assertEquals("Retained new tag", input(form(activity)!!).text.toString()) }
        val held = CountDownLatch(1)
        writer = Thread {
            TagRuntime.lock.lock()
            try {
                held.countDown()
                check(release.await(20, TimeUnit.SECONDS))
            } finally {
                TagRuntime.lock.unlock()
            }
        }.also { it.start() }
        assertTrue(held.await(2, TimeUnit.SECONDS))
        scenario.onActivity { activity -> (form(activity)!!.dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
        scenario.recreate()
        waitFor { form(it)?.dialog?.isShowing == true }
        scenario.onActivity { activity -> assertFalse((form(activity)!!.dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled) }
        release.countDown()
        writer?.join(5000)
        waitFor { activity ->
            form(activity) == null && editor(activity)?.dialog?.window?.decorView?.let { root -> descendants(root).filterIsInstance<CheckBox>().any { it.text.startsWith("Retained new tag") && it.isChecked } } == true
        }
        assertEquals(1, repository.definitions().tags.count { it.name == "Retained new tag" })
        val newId = repository.definitions().tags.single { it.name == "Retained new tag" }.id
        scenario.onActivity { activity -> find(editor(activity)!!.dialog!!.window!!.decorView, "Apply")!!.performClick() }
        waitFor { editor(it) == null }
        assertTrue(newId in repository.editor(StreamKey(stream.serviceId, stream.url)).assigned)
    }

    @Test fun conflictingDefinitionEditKeepsItsDraftAndErrorAcrossRotation() {
        val snapshot = repository.definitions()
        val original = snapshot.tags.single()
        scenario.onActivity { activity -> TagForms.tag(editor(activity)!!, snapshot, original) }
        waitFor { form(it)?.dialog?.isShowing == true }
        scenario.onActivity { activity -> input(form(activity)!!).setText("Local draft") }
        repository.editTag(original.id, "Concurrent AI rename", null, snapshot.version)
        scenario.onActivity { activity -> (form(activity)!!.dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
        waitFor { activity -> form(activity)?.let { input(it).error != null } == true }
        scenario.recreate()
        waitFor { form(it)?.dialog?.isShowing == true }
        scenario.onActivity { activity ->
            assertEquals("Local draft", input(form(activity)!!).text.toString())
            assertNotNull(input(form(activity)!!).error)
        }
        assertEquals("Concurrent AI rename", repository.definitions().tags.single().name)
        // The retained dialog now exposes current values and uses their revision for an
        // explicit second Save. It must not trap the draft on the original stale revision.
        scenario.onActivity { activity -> (form(activity)!!.dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
        waitFor { form(it) == null }
        assertEquals("Local draft", repository.definitions().tags.single().name)
    }

    @Test fun managerRefreshesConcurrentDefinitionsWithoutClosingAndReopening() {
        scenario.onActivity { activity -> editor(activity)!!.dismiss() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity { activity -> TagManagerFragment.show(activity.supportFragmentManager, longArrayOf(), longArrayOf()) }
        waitFor { activity -> activity.supportFragmentManager.findFragmentByTag("rv-tag-manager")?.let { (it as TagManagerFragment).dialog?.isShowing } == true }
        repository.createCategory("Created by AI")
        waitFor { activity ->
            val manager = activity.supportFragmentManager.findFragmentByTag("rv-tag-manager") as? TagManagerFragment
            manager?.dialog?.window?.decorView?.let { root -> descendants(root).filterIsInstance<TextView>().any { it.text.contains("Created by AI") } } == true
        }
        val category = repository.definitions().categories.single()
        repository.renameCategory(category.id, "Renamed by AI", repository.definitions().version)
        waitFor { activity ->
            val manager = activity.supportFragmentManager.findFragmentByTag("rv-tag-manager") as? TagManagerFragment
            manager?.dialog?.window?.decorView?.let { root -> descendants(root).filterIsInstance<TextView>().any { it.text.contains("Renamed by AI") } } == true
        }
    }

    private fun editor(activity: TagsActivity) = activity.supportFragmentManager.findFragmentByTag("rv-persistent-tag-editor") as? TagEditorFragment
    private fun form(activity: TagsActivity) = editor(activity)?.childFragmentManager?.findFragmentByTag(TagForms.DIALOG) as? TagDefinitionDialog
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun input(fragment: TagDefinitionDialog) = descendants(fragment.dialog!!.window!!.decorView).filterIsInstance<EditText>().single()
    private fun find(root: View, label: String) = descendants(root).filterIsInstance<TextView>().firstOrNull { it.text.toString() == label }
    private fun waitFor(predicate: (TagsActivity) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (System.nanoTime() < deadline) {
            var done = false
            scenario.onActivity { done = predicate(it) }
            if (done) return
            Thread.sleep(60)
        }
        error("Retained native form did not reach the expected state")
    }
}
