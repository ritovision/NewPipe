/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.custom.library.LibraryBindingStore
import org.schabi.newpipe.custom.library.LibraryRepository
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.testUtil.TestDatabase

/** Real Android lifecycle regression, with isolated in-memory databases and a retained writer. */
class TagEditorLifecycleTest {
    @Test fun saveAfterStateSavedDismissesSafelyOnResume() = exercise(false)

    @Test fun rotatingDuringSaveRetainsOneOperationAndClosesAfterSuccess() = exercise(true)

    private fun exercise(rotate: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LibraryRepository.forgetSessionForTest()
        val db = TestDatabase.createReplacingNewPipeDatabase()
        val path = context.cacheDir.toPath().resolve("tag-editor-${System.nanoTime()}.db")
        val store = TagStore(context, null)
        val repository = TagRepository(db, store, LibraryBindingStore(path))
        TagRuntime.registerForTest(db, repository)
        val item = StreamEntity(serviceId = 0, url = "https://example.org/lifecycle", title = "Lifecycle fixture", streamType = StreamType.VIDEO_STREAM, duration = 12, uploader = "Synthetic")
        val tag = repository.createTag("Test tag", null)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var retained: TagEditorSaveState? = null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch<TagsActivity>(Intent(context, TagsActivity::class.java))
        try {
            scenario.onActivity { activity ->
                TagEditorFragment.show(activity.supportFragmentManager, item)
                activity.supportFragmentManager.executePendingTransactions()
                val fragment = activity.supportFragmentManager.findFragmentByTag("rv-persistent-tag-editor")!!
                retained = ViewModelProvider(fragment)[TagEditorSaveState::class.java]
                retained!!.apply {
                    entered.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                    repository.apply(item, setOf(tag), 0)
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            if (rotate) scenario.recreate() else scenario.moveToState(Lifecycle.State.CREATED)
            release.countDown()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var saved = false
            while (System.nanoTime() < until && !saved) {
                instrumentation.runOnMainSync { saved = retained!!.result.value == TagEditorSaveState.Result.Saved }
                if (!saved) Thread.sleep(30)
            }
            assertTrue("Apply completed", saved)
            if (!rotate) {
                scenario.onActivity { assertNotNull(it.supportFragmentManager.findFragmentByTag("rv-persistent-tag-editor")) }
                scenario.moveToState(Lifecycle.State.RESUMED)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertNull(it.supportFragmentManager.findFragmentByTag("rv-persistent-tag-editor")) }
            assertEquals(setOf(tag), repository.editor(StreamKey(item.serviceId, item.url)).assigned)
            assertEquals(1L, repository.editor(StreamKey(item.serviceId, item.url)).revision)
        } finally {
            release.countDown()
            scenario.close()
            TagRuntime.unregister(db)
            store.close()
            db.close()
            LibraryRepository.forgetSessionForTest()
            LibraryBindingStore.deleteForDatabase(path)
        }
    }
}
