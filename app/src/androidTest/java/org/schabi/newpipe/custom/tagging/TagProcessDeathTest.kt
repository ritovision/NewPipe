/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.custom.library.LibraryBindingStore
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType

/** Explicit host-driven process death only; otherwise skipped by the regular native suite. */
class TagProcessDeathTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val root get() = context.cacheDir.toPath().resolve("tag-process-death-private")
    private fun phase(): String {
        val phase = InstrumentationRegistry.getArguments().getString("tag_crash_phase")
        assumeTrue("Only the explicit QA host can arm this fixture", phase in listOf("new:newpipe.db", "committed"))
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
        CatalogFileSwap.recover(live)
        val expected = if (phase == "committed") "new" else "old"
        NewPipeDatabase.openStagedCopy(context, live.resolve("newpipe.db")).use { db ->
            db.openHelper.readableDatabase.query("SELECT title FROM streams WHERE uid=1").use { c -> assertTrue(c.moveToFirst()); assertEquals(expected, c.getString(0)) }
            db.openHelper.readableDatabase.query("PRAGMA quick_check").use { c -> assertTrue(c.moveToFirst()); assertEquals("ok", c.getString(0)) }
            assertEquals(1, db.playlistStreamDAO().getOrderedStreamsOf(1).blockingFirst().size)
        }
        TagStore(context, live.resolve(TagStore.FILE_NAME).toString()).use { tags ->
            assertEquals(expected, tags.definitions().tags.single().name)
            assertEquals(setOf(1L), tags.assigned(StreamKey(0, "https://example.org/process-death")))
        }
        assertEquals(1L, LibraryBindingStore(live.resolve("newpipe.db")).read()!!.playlistId)
        assertFalse(Files.exists(live.resolve(CatalogFileSwap.JOURNAL)))
        println("TAG_REAL_PROCESS_DEATH_RECOVERED_$expected")
        CatalogFileSwap.deleteTree(root)
    }

    private fun createCatalog(directory: Path, name: String) {
        NewPipeDatabase.openStagedCopy(context, directory.resolve("newpipe.db")).use { db ->
            db.runInTransaction {
                val uid = db.streamDAO().upsert(StreamEntity(serviceId = 0, url = "https://example.org/process-death", title = name, streamType = StreamType.VIDEO_STREAM, duration = 10, uploader = "Synthetic"))
                val id = db.playlistDAO().insert(PlaylistEntity(name = "Library", isThumbnailPermanent = false, thumbnailStreamId = uid, displayIndex = 0))
                db.playlistStreamDAO().insert(PlaylistStreamEntity(id, uid, 0))
            }
        }
        TagStore(context, directory.resolve(TagStore.FILE_NAME).toString()).use { tags ->
            val id = tags.createTag(name, null)
            tags.setTags(StreamKey(0, "https://example.org/process-death"), setOf(id), 0)
        }
        LibraryBindingStore(directory.resolve("newpipe.db")).write(LibraryBindingStore.Binding(1, "Library"))
    }
}
