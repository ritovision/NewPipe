/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import kotlin.concurrent.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.schabi.newpipe.custom.aicontrol.AiDatabaseMigration
import org.schabi.newpipe.custom.aicontrol.RitoVisionDatabaseVersion
import org.schabi.newpipe.custom.tagging.CatalogFileSwap
import org.schabi.newpipe.custom.tagging.StreamKey
import org.schabi.newpipe.custom.tagging.TagRuntime
import org.schabi.newpipe.custom.tagging.TagStore
import org.schabi.newpipe.database.AppDatabase

class CloudDatabaseMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun priorFork9001UpgradesWithoutLosingNativeContentOrAiReceipts() {
        val name = "cloud-migration-9001-to-9002.db"
        helper.createDatabase(name, 9001).use { db ->
            db.execSQL("INSERT INTO streams(uid,service_id,url,title,stream_type,duration,uploader) VALUES(1,0,'https://example.test/old','Keep','VIDEO_STREAM',42,'Synthetic')")
            db.execSQL("INSERT INTO playlists(uid,name,is_thumbnail_permanent,thumbnail_stream_id,display_index) VALUES(3,'Existing',0,1,0)")
            db.execSQL("INSERT INTO playlist_stream_join VALUES(3,1,0)")
            db.execSQL("INSERT INTO rv_ai_command_receipts VALUES('device','epoch','client','request','fingerprint','[]','{}',999999)")
        }
        helper.runMigrationsAndValidate(name, RitoVisionDatabaseVersion.CURRENT, true, CloudDatabaseMigration.FROM_9001).use { db ->
            db.query("SELECT title FROM streams WHERE uid=1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Keep", c.getString(0))
            }
            db.query("SELECT COUNT(*) FROM playlist_stream_join WHERE playlist_id=3 AND stream_id=1").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM rv_ai_command_receipts").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM rv_cloud_apply_receipt").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test fun upstreamNineUsesTheExplicitTwoStepChain() {
        val name = "cloud-migration-9-to-9002.db"
        helper.createDatabase(name, 9).close()
        helper.runMigrationsAndValidate(
            name,
            RitoVisionDatabaseVersion.CURRENT,
            true,
            AiDatabaseMigration.FROM_UPSTREAM_9,
            CloudDatabaseMigration.FROM_9001
        ).use { db ->
            db.query("PRAGMA user_version").use { c ->
                c.moveToFirst()
                assertEquals(9002, c.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM rv_cloud_apply_receipt").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test fun tagFourToFivePreservesMappingsAndTagContent() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val dir = Files.createTempDirectory(app.noBackupFilesDir.toPath(), "cloud-tag-migration-")
        try {
            val path = dir.resolve("tags.db").toString()
            var oldContext: CloudIdentityStore.Context? = null
            var oldMaps = emptyMap<CloudIdentityStore.NativeId, String>()
            TagStore(app, path).use { store ->
                val tag = store.createTag("Keep", null)
                store.setTags(StreamKey(0, "https://example.test/old"), setOf(tag), 0)
                TagRuntime.lock.withLock {
                    store.transaction { db ->
                        val result = CloudIdentityStore.reconcile(
                            db,
                            CloudIdentityStore.Scope("service", "library"),
                            1,
                            2,
                            null,
                            setOf(CloudIdentityStore.NativeId(CloudIdentityStore.Kind.TAG, tag)),
                            enrollment = true
                        )
                        oldContext = result.first
                        oldMaps = result.second
                        db.execSQL("DROP TABLE cloud_apply_payload")
                        db.execSQL("DROP TABLE cloud_apply_pending")
                        db.execSQL("DROP TABLE cloud_sync_baseline")
                        db.version = 4
                    }
                }
            }
            TagStore(app, path).use { store ->
                assertEquals(5, store.writableDatabase.version)
                assertEquals(oldContext, CloudIdentityStore.context(store.readableDatabase))
                assertEquals(oldMaps, CloudIdentityStore.mappings(store.readableDatabase))
                assertEquals("Keep", store.definitions().tags.single().name)
                assertEquals(1, store.assigned(StreamKey(0, "https://example.test/old")).size)
                assertNull(CloudApplyStore.pending(store.readableDatabase))
                assertNull(CloudApplyStore.baseline(store.readableDatabase))
            }
        } finally {
            CatalogFileSwap.deleteTree(dir)
        }
    }
}
