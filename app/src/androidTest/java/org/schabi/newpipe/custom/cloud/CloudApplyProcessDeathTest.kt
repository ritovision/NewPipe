/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.custom.tagging.TagRuntime

/** Explicit host-only probes: real process death before Room commit, after it, and before tag completion. */
class CloudApplyProcessDeathTest {
    private var fixture: CloudApplyFixture? = null
    private fun directory(): File {
        val args = InstrumentationRegistry.getArguments()
        val token = args.getString("cloudProbe")
        assumeTrue("Requires the committed explicit host driver", token != null)
        require(token!!.matches(Regex("[a-z0-9-]{1,48}")))
        val context = ApplicationProvider.getApplicationContext<Context>()
        return File(context.cacheDir, "cloud-apply-probe-$token").also { it.mkdirs() }
    }
    private fun phase(): CloudNativeApply.Phase {
        val name = InstrumentationRegistry.getArguments().getString("cloudPhase")
        require(name in setOf("BEFORE_ROOM_COMMIT", "ROOM_COMMITTED", "BEFORE_TAG_COMMIT"))
        return CloudNativeApply.Phase.valueOf(name!!)
    }
    private fun open(dir: File): CloudApplyFixture = CloudApplyFixture(ApplicationProvider.getApplicationContext(), dir.toPath()).also { fixture = it }
    private fun writeSync(file: File, content: String) {
        FileOutputStream(file).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    @After fun close() {
        fixture?.close()
    }

    @Test fun prepareCommittedIdentities() {
        val dir = directory()
        check(!File(dir, "prepared").exists())
        val f = open(dir)
        f.initialize()
        val before = f.seed()
        val target = f.target(before)
        writeSync(File(dir, "before.json"), JsonWriter.string().`object`(CloudApplyPlanJson.catalog(before.catalog)).done())
        writeSync(File(dir, "target.json"), JsonWriter.string().`object`(CloudApplyPlanJson.catalog(target)).done())
        writeSync(File(dir, "before-revision"), before.tagRevision.toString())
        writeSync(File(dir, "incarnation"), before.identities.incarnation)
        writeSync(File(dir, "phase"), phase().name)
        writeSync(File(dir, "prepared"), "synthetic-native-catalog")
    }

    @Test fun holdChangedIdentityTransactionForHostKill() {
        val dir = directory()
        check(File(dir, "prepared").readText() == "synthetic-native-catalog")
        val wanted = phase()
        check(File(dir, "phase").readText() == wanted.name)
        val f = open(dir)
        f.initialize()
        val before = f.capture()
        val target = CloudCatalogCodec.read(File(dir, "target.json").readText())
        val key = target.library.first()
        CloudNativeApply(f.repo) { point ->
            if (point == wanted) {
                check(TagRuntime.lock.isHeldByCurrentThread)
                val pending = checkNotNull(CloudApplyStore.pending(f.repo.store.readableDatabase))
                writeSync(File(dir, "pending-operation"), pending.operation)
                writeSync(File(dir, "inside-transaction"), "ready-for-host-force-stop")
                Thread.sleep(60_000)
                error("Host did not terminate the isolated cloud apply process")
            }
        }.apply(before, f.head(target), target, listOf(f.hint(key)))
    }

    @Test fun verifyRollbackAfterActualProcessDeath() {
        val dir = directory()
        check(File(dir, "inside-transaction").readText() == "ready-for-host-force-stop")
        val expectedPhase = phase()
        check(File(dir, "phase").readText() == expectedPhase.name)
        val f = open(dir)
        // Do not call initialize before inspecting the durable evidence left by the killed process.
        val pending = checkNotNull(CloudApplyStore.pending(f.repo.store.readableDatabase))
        assertEquals(File(dir, "pending-operation").readText(), pending.operation)
        val before = CloudCatalogCodec.read(File(dir, "before.json").readText())
        val target = CloudCatalogCodec.read(File(dir, "target.json").readText())
        val oldRevision = File(dir, "before-revision").readText().toLong()
        val committed = expectedPhase != CloudNativeApply.Phase.BEFORE_ROOM_COMMIT
        if (committed) {
            assertEquals(pending.operation, f.db.cloudApplyReceiptDao().current()!!.operationId)
        } else {
            assertNull(f.db.cloudApplyReceiptDao().current())
        }
        f.initialize() // Ordinary tag/native admission drives the actual recovery implementation.
        val after = f.capture()
        assertEquals(File(dir, "incarnation").readText(), after.identities.incarnation)
        assertEquals(CloudCatalogCodec.fingerprint(if (committed) target else before), after.fingerprint)
        assertEquals(oldRevision + if (committed) 1 else 0, f.repo.store.version())
        assertNull(CloudApplyStore.pending(f.repo.store.readableDatabase))
        if (committed) {
            assertEquals(f.head(target), CloudApplyStore.baseline(f.repo.store.readableDatabase)!!.head)
        } else {
            assertNull(CloudApplyStore.baseline(f.repo.store.readableDatabase))
        }
        repeat(3) {
            f.repo.definitions()
            f.capture()
        }
        assertEquals(oldRevision + if (committed) 1 else 0, f.repo.store.version())
        f.repo.store.readableDatabase.rawQuery("PRAGMA quick_check", null).use { c ->
            check(c.moveToFirst())
            assertEquals("ok", c.getString(0))
        }
        assertNotNull(f.repo.binding.read())
        writeSync(File(dir, "verified"), "rollback-restored-original-identities")
        println("CLOUD_APPLY_PROCESS_DEATH_PASS phase=${expectedPhase.name} room_committed=$committed")
    }
}
