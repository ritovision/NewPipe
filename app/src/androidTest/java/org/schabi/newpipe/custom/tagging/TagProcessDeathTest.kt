/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.custom.library.LibraryBindingStore
import org.schabi.newpipe.custom.library.LibraryRepository
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType

/** Explicit host-driven process death only; otherwise skipped by the regular native suite. */
class TagProcessDeathTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val root get() = context.cacheDir.toPath().resolve("tag-process-death-private")
    private fun phase(): String {
        val phase = InstrumentationRegistry.getArguments().getString("tag_crash_phase")
        assumeTrue("Only the explicit QA host can arm this fixture", phase in listOf("new:newpipe.db", "new:ritovision-library.binding", "committed"))
        return phase!!
    }

    @Test fun prepareAndWaitForHostTermination() {
        val phase = phase()
        CatalogFileSwap.deleteTree(root)
        val live = Files.createDirectories(root.resolve("live"))
        val next = Files.createDirectories(root.resolve("next"))
        createCatalog(live, "old")
        createCatalog(next, "new")
        CatalogFileSwap.fault = { point ->
            if (point == phase) {
                Files.write(root.resolve("READY"), point.toByteArray())
                CatalogFileSwap.syncFile(root.resolve("READY"))
                // The primary host terminates ONLY this QA package on emulator-5574.
                // An accidental ordinary test run cannot reach this wait without the argument.
                Thread.sleep(180000)
                error("Host did not terminate the explicitly armed QA process")
            }
        }
        CatalogFileSwap.replace(live, listOf("newpipe.db", TagStore.FILE_NAME, LibraryBindingStore.FILE_NAME).associateWith { next.resolve(it) })
        error("Host process termination was expected")
    }

    @Test fun recoverAfterHostTermination() {
        val phase = phase()
        assertTrue(Files.exists(root.resolve("READY")))
        CatalogFileSwap.fault = null
        val live = root.resolve("live")
        val expected = if (phase == "committed") "new" else "old"
        val expectedId = if (expected == "new") 7L else 1L
        TagRuntime.restoring = false
        NewPipeDatabase.close()
        LibraryRepository.forgetSessionForTest()
        val ownedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = if (name.startsWith("/")) File(name) else live.resolve(name).toFile()
        }
        val db = NewPipeDatabase.getInstance(ownedContext)
        try {
            // Intentionally initialize Library FIRST, not an explicit recovery helper.
            // This must recover before reading a possibly swapped binding with a reused ID.
            val snapshot = TagRuntime.get(ownedContext).snapshot()
            assertEquals(expectedId, LibraryRepository.boundLibraryId(db))
            assertEquals(1, snapshot.items.size)
            db.openHelper.readableDatabase.query("SELECT title FROM streams WHERE uid=1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(expected, c.getString(0))
            }
            db.openHelper.readableDatabase.query("PRAGMA quick_check").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("ok", c.getString(0))
            }
            assertEquals(1, db.playlistStreamDAO().getOrderedStreamsOf(expectedId).blockingFirst().size)
        } finally {
            db.close()
        }
        TagStore(context, live.resolve(TagStore.FILE_NAME).toString()).use { tags ->
            assertEquals(expected, tags.definitions().tags.single().name)
            assertEquals(setOf(1L), tags.assigned(StreamKey(0, "https://example.org/process-death")))
        }
        assertEquals(expectedId, LibraryBindingStore(live.resolve("newpipe.db")).read()!!.playlistId)
        assertFalse(Files.exists(live.resolve(CatalogFileSwap.JOURNAL)))
        println("TAG_REAL_PROCESS_DEATH_RECOVERED_$expected")
        CatalogFileSwap.deleteTree(root)
    }

    private fun createCatalog(directory: Path, name: String) {
        val libraryId = if (name == "new") 7L else 1L
        val db = NewPipeDatabase.openStagedCopy(context, directory.resolve("newpipe.db"))
        try {
            db.runInTransaction {
                val uid = db.streamDAO().upsert(StreamEntity(serviceId = 0, url = "https://example.org/process-death", title = name, streamType = StreamType.VIDEO_STREAM, duration = 10, uploader = "Synthetic"))
                db.openHelper.writableDatabase.execSQL("INSERT INTO playlists(uid,name,is_thumbnail_permanent,thumbnail_stream_id,display_index) VALUES(?,'Library',0,?,0)", arrayOf(libraryId, uid))
                if (name == "old") db.openHelper.writableDatabase.execSQL("INSERT INTO playlists(uid,name,is_thumbnail_permanent,thumbnail_stream_id,display_index) VALUES(7,'Library',0,-1,1)")
                db.playlistStreamDAO().insert(PlaylistStreamEntity(libraryId, uid, 0))
            }
        } finally {
            db.close()
        }
        TagStore(context, directory.resolve(TagStore.FILE_NAME).toString()).use { tags ->
            val id = tags.createTag(name, null)
            tags.setTags(StreamKey(0, "https://example.org/process-death"), setOf(id), 0)
        }
        LibraryBindingStore(directory.resolve("newpipe.db")).write(LibraryBindingStore.Binding(libraryId, "Library"))
    }
}
