/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.schabi.newpipe.database.AppDatabase

class AiDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun upgradeFromCompletedLibraryPreservesCatalogAndAddsReceiptTable() {
        val name = "ai-control-migration-synthetic.db"
        helper.createDatabase(name, 9).use { db ->
            db.execSQL("INSERT INTO streams(uid, service_id, url, title, stream_type, duration, uploader) VALUES(1, 0, 'https://example.org/a', 'Synthetic', 'VIDEO_STREAM', 120, 'Tester')")
            db.execSQL("INSERT INTO playlists(uid, name, is_thumbnail_permanent, thumbnail_stream_id, display_index) VALUES(5, 'Synthetic', 0, 1, 0)")
            db.execSQL("INSERT INTO playlist_stream_join(playlist_id, stream_id, join_index) VALUES(5, 1, 0)")
        }
        helper.runMigrationsAndValidate(name, AiDatabaseMigration.VERSION, true, AiDatabaseMigration.FROM_UPSTREAM_9).use { db ->
            db.query("SELECT title FROM streams WHERE uid = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Synthetic", cursor.getString(0))
            }
            db.query("SELECT stream_id FROM playlist_stream_join WHERE playlist_id = 5").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
            }
            db.query("SELECT COUNT(*) FROM rv_ai_command_receipts").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            db.query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(10, cursor.getInt(0))
            }
        }
    }
}
