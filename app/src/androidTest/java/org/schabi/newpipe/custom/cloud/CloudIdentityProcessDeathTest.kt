/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.custom.cloud.CloudIdentityStore.Kind
import org.schabi.newpipe.custom.cloud.CloudIdentityStore.NativeId
import org.schabi.newpipe.custom.tagging.TagRuntime
import org.schabi.newpipe.custom.tagging.TagStore

/** Host-driven only: prepare -> force-stop while transaction is open -> verify in a new process. */
class CloudIdentityProcessDeathTest {
    private fun directory(): File {
        val name = InstrumentationRegistry.getArguments().getString("cloudProbe")
        assumeTrue("Requires the isolated host process-death driver", name != null)
        require(name!!.matches(Regex("[a-z0-9-]{1,48}")))
        val app = ApplicationProvider.getApplicationContext<Context>()
        return File(app.cacheDir, "cloud-identity-probe-$name").also { it.mkdirs() }
    }
    private fun writeSync(file: File, text: String) {
        FileOutputStream(file).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }
    private fun identity(store: TagStore): String {
        val c = checkNotNull(CloudIdentityStore.context(store.readableDatabase))
        val m = CloudIdentityStore.mappings(store.readableDatabase)
        return listOf(
            c.scope.service,
            c.scope.library,
            c.incarnation,
            c.libraryId.toString(),
            c.unavailableId.toString(),
            m.entries.sortedBy { it.key.kind.name + ":" + it.key.id }.joinToString("|") { "${it.key.kind}:${it.key.id}=${it.value}" }
        ).joinToString("\n")
    }

    @Test fun prepareCommittedIdentities() {
        val dir = directory()
        val expected = File(dir, "expected.txt")
        check(!expected.exists()) { "Refusing to reset an existing process probe" }
        val app = ApplicationProvider.getApplicationContext<Context>()
        TagStore(app, File(dir, "probe.db").absolutePath).use { store ->
            TagRuntime.lock.withLock {
                store.transaction { db ->
                    CloudIdentityStore.reconcile(
                        db,
                        CloudIdentityStore.Scope("process-test", "library-test"),
                        1,
                        2,
                        null,
                        setOf(NativeId(Kind.PLAYLIST, 10), NativeId(Kind.TAG, 20)),
                        enrollment = true
                    )
                }
            }
            writeSync(expected, identity(store))
            assertEquals(2, CloudIdentityStore.mappings(store.readableDatabase).size)
        }
        writeSync(File(dir, "prepared"), "committed")
    }

    @Test fun holdChangedIdentityTransactionForHostKill() {
        val dir = directory()
        check(File(dir, "prepared").readText() == "committed")
        val app = ApplicationProvider.getApplicationContext<Context>()
        TagStore(app, File(dir, "probe.db").absolutePath).use { store ->
            assertEquals(File(dir, "expected.txt").readText(), identity(store))
            TagRuntime.lock.withLock {
                store.transaction { db ->
                    CloudIdentityStore.discard(db)
                    CloudIdentityStore.reconcile(
                        db,
                        CloudIdentityStore.Scope("interrupted", "must-not-commit"),
                        100,
                        200,
                        null,
                        setOf(NativeId(Kind.TAG, 999)),
                        enrollment = true
                    )
                    writeSync(File(dir, "inside-transaction"), "ready-for-host-force-stop")
                    Thread.sleep(60_000)
                    error("Host did not terminate the isolated test process within the probe window")
                }
            }
        }
    }

    @Test fun verifyRollbackAfterActualProcessDeath() {
        val dir = directory()
        check(File(dir, "inside-transaction").readText() == "ready-for-host-force-stop")
        val app = ApplicationProvider.getApplicationContext<Context>()
        TagStore(app, File(dir, "probe.db").absolutePath).use { store ->
            assertEquals(File(dir, "expected.txt").readText(), identity(store))
            assertNotNull(CloudIdentityStore.context(store.readableDatabase))
            assertFalse(CloudIdentityStore.mappings(store.readableDatabase).containsKey(NativeId(Kind.TAG, 999)))
            store.readableDatabase.rawQuery("PRAGMA quick_check", null).use { c ->
                check(c.moveToFirst())
                assertEquals("ok", c.getString(0))
            }
            assertEquals(4, store.readableDatabase.version)
        }
        writeSync(File(dir, "verified"), "rollback-restored-original-identities")
        println("CLOUD_IDENTITY_PROCESS_DEATH_PASS")
    }
}
