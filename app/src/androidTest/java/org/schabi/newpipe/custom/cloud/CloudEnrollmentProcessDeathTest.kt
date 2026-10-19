/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.custom.library.LibraryRepository
import org.schabi.newpipe.custom.library.UnavailableRepository
import org.schabi.newpipe.custom.tagging.TagRepository
import org.schabi.newpipe.custom.tagging.TagRuntime
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.model.PlaylistEntity

/** Host kills QA process after the binding anchor is durable but before tag enrollment commits. */
class CloudEnrollmentProcessDeathTest {
    private val scope = CloudIdentityStore.Scope("process-enroll", "library-test")
    private fun directory(): File {
        val token = InstrumentationRegistry.getArguments().getString("cloudProbe")
        assumeTrue("Requires the explicit host process-death driver", token != null)
        require(token!!.matches(Regex("[a-z0-9-]{1,48}")))
        return File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "cloud-enrollment-probe-$token").also { it.mkdirs() }
    }
    private fun writeSync(file: File, text: String) {
        FileOutputStream(file).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }
    private fun open(dir: File): Pair<AppDatabase, TagRepository> {
        NewPipeDatabase.close()
        TagRuntime.restoring = false
        LibraryRepository.forgetSessionForTest()
        UnavailableRepository.reset()
        val app = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = dir
            override fun getDatabasePath(name: String): File = if (name.startsWith("/")) File(name) else File(dir, name)
        }
        val db = NewPipeDatabase.getInstance(context)
        val repo = TagRuntime.get(context)
        repo.definitions()
        return db to repo
    }

    @After fun close() {
        NewPipeDatabase.close()
        TagRuntime.restoring = false
        LibraryRepository.forgetSessionForTest()
        UnavailableRepository.reset()
    }

    @Test fun prepareCommittedIdentities() {
        val dir = directory()
        check(!File(dir, "prepared").exists()) { "Refusing to overwrite a prior probe" }
        val (db, repo) = open(dir)
        db.runInTransaction {
            db.playlistDAO().insert(PlaylistEntity(name = "Keep", isThumbnailPermanent = false, thumbnailStreamId = -1, displayIndex = 0))
        }
        assertNull(CloudIdentityStore.context(repo.store.readableDatabase))
        assertNull(repo.binding.read()!!.cloudIncarnation)
        writeSync(File(dir, "prepared"), "unenrolled-native-catalog")
    }

    @Test fun holdChangedIdentityTransactionForHostKill() {
        val dir = directory()
        check(File(dir, "prepared").readText() == "unenrolled-native-catalog")
        val (_, repo) = open(dir)
        CloudNativeCatalog(repo) {
            writeSync(File(dir, "orphan-marker"), checkNotNull(repo.binding.read()!!.cloudIncarnation))
            writeSync(File(dir, "inside-transaction"), "ready-for-host-force-stop")
            Thread.sleep(60_000)
            error("Host did not stop the QA process during enrollment")
        }.enroll(scope)
    }

    @Test fun verifyRollbackAfterActualProcessDeath() {
        val dir = directory()
        check(File(dir, "inside-transaction").readText() == "ready-for-host-force-stop")
        val orphan = File(dir, "orphan-marker").readText()
        val (db, repo) = open(dir)
        assertEquals(orphan, repo.binding.read()!!.cloudIncarnation)
        assertNull(CloudIdentityStore.context(repo.store.readableDatabase))
        assertTrue(CloudIdentityStore.mappings(repo.store.readableDatabase).isEmpty())
        assertEquals(
            "CLOUD_ENROLLMENT_REQUIRED",
            assertThrows(IllegalStateException::class.java) {
                CloudNativeCatalog(repo).capture(scope, orphan)
            }.message
        )
        assertEquals(listOf("Library", "Unavailable", "Keep"), db.playlistDAO().getAll().blockingFirst().map { it.name })
        val next = CloudNativeCatalog(repo).enroll(scope)
        assertNotEquals(orphan, next.identities.incarnation)
        assertEquals(next.identities.incarnation, repo.binding.read()!!.cloudIncarnation)
        assertEquals("Keep", next.catalog.playlists.single().name)
        writeSync(File(dir, "verified"), "rollback-restored-original-identities")
        println("CLOUD_ENROLLMENT_PROCESS_DEATH_PASS")
    }
}
