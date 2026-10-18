/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.library

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.TestScheduler
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.custom.tagging.TagRepository
import org.schabi.newpipe.custom.tagging.TagRuntime
import org.schabi.newpipe.custom.tagging.TagStore
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.playlist.LocalPlaylistFragment
import org.schabi.newpipe.local.playlist.LocalPlaylistManager
import org.schabi.newpipe.testUtil.TestDatabase
import org.schabi.newpipe.util.NavigationHelper

/** Actual Fragment, toolbar/menu and dialogs on an explicitly supplied secondary display only. */
class UnavailableUiAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var tags: TagStore
    private lateinit var bindingPath: Path
    private lateinit var manager: LocalPlaylistManager
    private var displayId = -1
    private var unavailable = 0L
    private var library = 0L
    private var registered = false

    @Before fun setup() {
        displayId = InstrumentationRegistry.getArguments().getString("qaDisplayId")?.toIntOrNull() ?: -1
        assumeTrue("Pass an explicitly authorized scrcpy virtual display; never launch on display 0", displayId > 0)
        context = ApplicationProvider.getApplicationContext()
        assertTrue("This suite must only run in a separate debug QA package", context.packageName.contains("debug.featureunavailable"))
        TagRuntime.restoring = false
        NewPipeDatabase.close()
        LibraryRepository.forgetSessionForTest()
        db = TestDatabase.createReplacingNewPipeDatabase()
        bindingPath = context.cacheDir.toPath().resolve("unavailable-ui-${UUID.randomUUID()}.db")
        val binding = LibraryBindingStore(bindingPath)
        tags = TagStore(context, null)
        val repo = TagRepository(db, tags, binding)
        TagRuntime.registerForTest(db, repo)
        registered = true
        library = LibraryRepository.initialize(db, binding)
        unavailable = UnavailableRepository.boundId(db)!!
        manager = LocalPlaylistManager(db)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        scenario = ActivityScenario.launch(intent, options)
        scenario!!.onActivity {
            assertEquals("QA activity escaped the selected virtual display", displayId, it.display!!.displayId)
            NavigationHelper.openLocalPlaylistFragment(it.supportFragmentManager, unavailable, "Unavailable")
            it.supportFragmentManager.executePendingTransactions()
        }
        awaitUi { current(it)?.let { f -> isLoaded(f) } == true && menu(it, R.id.menu_item_clear_unavailable)?.isVisible == true }
    }

    @After fun cleanup() {
        scenario?.close()
        scenario = null
        if (registered) {
            instrumentation.waitForIdleSync()
            TagRuntime.unregister(db)
            tags.close()
            db.close()
            LibraryRepository.forgetSessionForTest()
            LibraryBindingStore.deleteForDatabase(bindingPath)
            TagRuntime.restoring = false
        }
    }

    private fun current(activity: MainActivity) = activity.supportFragmentManager.findFragmentById(R.id.fragment_holder) as? LocalPlaylistFragment
    private fun menu(activity: MainActivity, id: Int) = activity.findViewById<Toolbar>(R.id.toolbar).menu.findItem(id)
    private fun isLoaded(fragment: LocalPlaylistFragment): Boolean = (field(fragment, "isLoadingComplete") as AtomicBoolean).get()
    private fun field(fragment: LocalPlaylistFragment, name: String): Any? = LocalPlaylistFragment::class.java.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(fragment)
    }
    private fun contents(id: Long) = manager.getPlaylistStreams(id).blockingFirst().map { it.streamId }
    private fun add(name: String): Long {
        val media = StreamEntity(
            serviceId = 0,
            url = "https://example.org/ui-$name",
            title = "QA $name",
            streamType = StreamType.VIDEO_STREAM,
            duration = 120,
            uploader = "Synthetic QA"
        )
        manager.appendToPlaylist(library, listOf(media)).blockingGet()
        return db.streamDAO().getStream(0, media.url).blockingFirst().single().uid
    }
    private fun mark(id: Long) = UnavailableRepository.setMarked(context, id, true).blockingAwait()
    private fun awaitUi(condition: (MainActivity) -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + 15000
        while (android.os.SystemClock.uptimeMillis() < end) {
            instrumentation.waitForIdleSync()
            var ready = false
            scenario!!.onActivity { ready = condition(it) }
            if (ready) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for actual native UI on virtual display $displayId")
    }
    private fun clickAction(id: Int) {
        scenario!!.onActivity {
            val action = menu(it, id)!!
            assertTrue(action.isVisible && action.isEnabled)
            assertTrue(current(it)!!.onOptionsItemSelected(action))
        }
        awaitUi { dialogButton(android.R.id.button1) != null }
    }
    private fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    private fun roots() = WindowInspector.getGlobalWindowViews().filter { it.display?.displayId == displayId && it.isShown }
    private fun dialogButton(id: Int): View? = roots().asReversed().firstNotNullOfOrNull { it.findViewById<View>(id)?.takeIf(View::isShown) }
    private fun pressDialog(id: Int) {
        scenario!!.onActivity { assertTrue(dialogButton(id)!!.performClick()) }
    }
    private fun screenshot(name: String) {
        scenario!!.onActivity {
            val root = roots().last()
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val folder = context.cacheDir.toPath().resolve("unavailable-ui-acceptance")
            Files.createDirectories(folder)
            Files.newOutputStream(folder.resolve("$name.png")).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            bitmap.recycle()
        }
    }

    @Test fun menuTracksEmptyPopulatedAndClearedWithoutReopeningScreen() {
        scenario!!.onActivity { assertFalse(menu(it, R.id.menu_item_clear_unavailable)!!.isEnabled) }
        val id = add("menu")
        mark(id)
        awaitUi { menu(it, R.id.menu_item_clear_unavailable)?.isEnabled == true }
        clickAction(R.id.menu_item_clear_unavailable)
        screenshot("clear-confirmation")
        pressDialog(android.R.id.button2)
        assertEquals(listOf(id), contents(unavailable))
        clickAction(R.id.menu_item_clear_unavailable)
        pressDialog(android.R.id.button1)
        awaitUi { menu(it, R.id.menu_item_clear_unavailable)?.isEnabled == false }
        assertTrue(contents(unavailable).isEmpty())
        assertEquals(listOf(id), contents(library))
        scenario!!.onActivity { assertFalse(menu(it, R.id.menu_item_remove_all_unavailable)!!.isEnabled) }
        mark(id)
        awaitUi { menu(it, R.id.menu_item_remove_all_unavailable)?.isEnabled == true }
        clickAction(R.id.menu_item_remove_all_unavailable)
        scenario!!.onActivity {
            assertEquals(context.getString(R.string.rv_unavailable_remove_everywhere), (dialogButton(android.R.id.button1) as TextView).text.toString())
        }
        screenshot("remove-everywhere-confirmation")
        pressDialog(android.R.id.button2)
        assertEquals(listOf(id), contents(library))
        clickAction(R.id.menu_item_remove_all_unavailable)
        pressDialog(android.R.id.button1)
        awaitUi { menu(it, R.id.menu_item_remove_all_unavailable)?.isEnabled == false }
        assertTrue(contents(library).isEmpty())
        println("UNAVAILABLE_NATIVE_MENU_TRANSITIONS_PASS")
    }

    @Test fun confirmedClearSurvivesReloadBeforeItsIoTaskRuns() {
        val id = add("clear-reload")
        mark(id)
        awaitUi { menu(it, R.id.menu_item_clear_unavailable)?.isEnabled == true }
        clickAction(R.id.menu_item_clear_unavailable)
        val scheduler = TestScheduler()
        val old = RxJavaPlugins.getIoSchedulerHandler()
        try {
            RxJavaPlugins.setIoSchedulerHandler { scheduler }
            pressDialog(android.R.id.button1)
            scenario!!.onActivity { current(it)!!.startLoading(false) }
            assertEquals(listOf(id), UnavailableRepository.ids(db, unavailable))
            scheduler.triggerActions()
        } finally {
            RxJavaPlugins.setIoSchedulerHandler(old)
        }
        awaitUi { menu(it, R.id.menu_item_clear_unavailable)?.isEnabled == false }
        assertTrue(contents(unavailable).isEmpty())
        assertEquals(listOf(id), contents(library))
        println("UNAVAILABLE_CONFIRMED_CLEAR_SURVIVES_RELOAD_PASS")
    }

    @Test fun twoDeferredMarksSurviveLeavingTheActualFragment() {
        val a = add("detach-a")
        val b = add("detach-b")
        mark(a)
        mark(b)
        awaitUi { current(it)?.let { f -> isLoaded(f) && (field(f, "loadedStreamIds") as List<*>).size == 2 } == true }
        val items = manager.getPlaylistStreams(unavailable).blockingFirst()
        val tasks = ArrayDeque<Runnable>()
        val queue = PlaylistSaveQueue(Executor { tasks.addLast(it) }) { expected, desired -> manager.savePlaylistSnapshot(unavailable, expected, desired) }
        queue.submit(listOf(a, b), listOf(b, a))
        lateinit var detached: LocalPlaylistFragment
        scenario!!.onActivity { activity ->
            val fragment = current(activity)!!
            detached = fragment
            LocalPlaylistFragment::class.java.getDeclaredField("playlistSaveQueue").also { it.isAccessible = true }.set(fragment, queue)
            val action = LocalPlaylistFragment::class.java.getDeclaredMethod("markUnavailablePreservingEdits", Context::class.java, PlaylistStreamEntry::class.java, Boolean::class.javaPrimitiveType).also { it.isAccessible = true }
            items.forEach { action.invoke(fragment, activity, it, false) }
            NavigationHelper.openLocalPlaylistFragment(activity.supportFragmentManager, library, "Library")
            activity.supportFragmentManager.executePendingTransactions()
        }
        awaitUi { detached.view == null }
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
        val end = android.os.SystemClock.uptimeMillis() + 10000
        while (contents(unavailable).isNotEmpty() && android.os.SystemClock.uptimeMillis() < end) {
            instrumentation.waitForIdleSync()
            Thread.sleep(25)
        }
        assertTrue(contents(unavailable).isEmpty())
        assertEquals(setOf(a, b), contents(library).toSet())
        println("UNAVAILABLE_TWO_DEFERRED_MARKS_SURVIVE_NAVIGATION_PASS")
    }
}
