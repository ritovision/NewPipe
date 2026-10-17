/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.schabi.newpipe.custom.library.LibraryBindingStore
import org.schabi.newpipe.custom.library.LibraryRepository
import org.schabi.newpipe.testUtil.TestDatabase
import org.schabi.newpipe.testUtil.TrampolineSchedulerRule

class TagScaleTest {
    @get:Rule val trampoline = TrampolineSchedulerRule()

    @Test fun twentyThousandSavedStreamsFilterWithoutTruncationAndCategoryDeleteKeepsMedia() {
        TagRuntime.restoring = false
        LibraryRepository.forgetSessionForTest()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bindingPath = context.cacheDir.toPath().resolve("scale-${UUID.randomUUID()}.db")
        val db = TestDatabase.createReplacingNewPipeDatabase()
        val tags = TagStore(context, null)
        val binding = LibraryBindingStore(bindingPath)
        val repository = TagRepository(db, tags, binding)
        TagRuntime.registerForTest(db, repository)
        try {
            val libraryId = LibraryRepository.initialize(db, binding)
            val category = repository.createCategory("Scale")
            val even = repository.createTag("Even", category)
            val thirds = repository.createTag("Thirds", category)
            db.runInTransaction {
                val insert = db.openHelper.writableDatabase.compileStatement("INSERT INTO streams(uid,service_id,url,title,stream_type,duration,uploader) VALUES(?,0,?,?,'VIDEO_STREAM',60,'Synthetic')")
                val member = db.openHelper.writableDatabase.compileStatement("INSERT INTO playlist_stream_join(playlist_id,stream_id,join_index) VALUES(?,?,?)")
                try {
                    for (index in 0 until 20000) {
                        val uid = index + 1L
                        insert.bindLong(1, uid)
                        insert.bindString(2, "https://example.org/scale/$index")
                        insert.bindString(3, "Stream %05d".format(index))
                        insert.executeInsert()
                        member.bindLong(1, libraryId)
                        member.bindLong(2, uid)
                        member.bindLong(3, index.toLong())
                        member.executeInsert()
                    }
                } finally {
                    insert.close()
                    member.close()
                }
            }
            tags.transaction { sql ->
                val insert = sql.compileStatement("INSERT INTO assignment(service_id,url,tag_id) VALUES(0,?,?)")
                try {
                    for (index in 0 until 20000) {
                        insert.bindString(1, "https://example.org/scale/$index")
                        if (index % 2 == 0) {
                            insert.bindLong(2, even)
                            insert.executeInsert()
                        }
                        if (index % 3 == 0) {
                            insert.bindLong(2, thirds)
                            insert.executeInsert()
                        }
                    }
                } finally {
                    insert.close()
                }
            }
            val start = SystemClock.elapsedRealtime()
            val snapshot = repository.snapshot()
            assertEquals(20000, snapshot.items.size)
            assertEquals(3334, TagFilter(included = setOf(even, thirds)).select(snapshot.items).size)
            assertEquals(6666, TagFilter(included = setOf(even), excluded = setOf(thirds)).select(snapshot.items).size)
            assertEquals(6667, TagFilter(untagged = true).select(snapshot.items).size)
            assertEquals("Stream 19999", TagFilter(sort = TagSort.TITLE_DESC).select(snapshot.items).first().stream.title)
            val elapsed = SystemClock.elapsedRealtime() - start
            println("TAG_SCALE_20000_SNAPSHOT_AND_FILTER_MS=$elapsed")
            assertTrue("Full 20k read/filter exceeded 30 seconds", elapsed < 30000)
            repository.delete(repository.deletionPlan(category, true))
            assertEquals(20000, TagFilter(untagged = true).select(repository.snapshot().items).size)
            assertTrue(repository.definitions().tags.isEmpty())
        } finally {
            TagRuntime.unregister(db)
            tags.close()
            db.close()
            LibraryBindingStore.deleteForDatabase(bindingPath)
            LibraryRepository.forgetSessionForTest()
        }
    }
}
