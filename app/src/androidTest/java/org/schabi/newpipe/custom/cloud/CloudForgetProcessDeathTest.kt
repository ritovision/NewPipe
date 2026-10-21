/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real host kills at each independent Forget boundary. Only synthetic native stores. */
class CloudForgetProcessDeathTest {
    private var fixture: CloudApplyFixture? = null
    private fun directory(): File {
        val token = InstrumentationRegistry.getArguments().getString("cloudProbe")
        assumeTrue("Explicit host driver required", token != null)
        require(token!!.matches(Regex("[a-z0-9-]{1,48}")))
        return File(ApplicationProvider.getApplicationContext<Context>().noBackupFilesDir, "cloud-forget-probe-$token").also { it.mkdirs() }
    }
    private fun phase() = checkNotNull(InstrumentationRegistry.getArguments().getString("cloudPhase")).also {
        require(it in setOf("off", "before-witness-commit", "witness", "metadata", "binding", "connection"))
    }
    private fun open(dir: File) = CloudApplyFixture(ApplicationProvider.getApplicationContext(), dir.toPath()).also { fixture = it }
    private fun writeSync(file: File, value: String) = FileOutputStream(file).use {
        it.write(value.toByteArray(Charsets.UTF_8))
        it.fd.sync()
    }
    private fun controller(f: CloudApplyFixture, store: CloudConnectionStore, peer: CloudFakePeer, point: ((String) -> Unit)? = null) = CloudSyncController(f.context, store, { f.repo }, peer::client, { _: CloudNativeSnapshot -> }, forgetCheckpoint = point)

    @After fun close() {
        fixture?.close()
    }

    @Test fun prepareCommittedIdentities() {
        val dir = directory()
        check(!File(dir, "prepared").exists())
        val f = open(dir)
        f.initialize()
        val before = f.seed()
        val peer = CloudFakePeer(f.scope).also { it.external(f.target(before)) }
        val connection = CloudConnectionStore(f.context, File(dir, "connection"))
        val c = controller(f, connection, peer)
        c.connect("https://example.test", peer.tokenA)
        c.confirm(c.prepare(true))
        assertNotNull(f.db.cloudApplyReceiptDao().current())
        writeSync(File(dir, "native-digest"), CloudRoomContentDigest.withoutCloudWitness(f.db.openHelper.readableDatabase))
        writeSync(File(dir, "definitions"), f.repo.definitions().toString())
        writeSync(File(dir, "phase"), phase())
        writeSync(File(dir, "prepared"), "synthetic-forget")
    }

    @Test fun holdChangedIdentityTransactionForHostKill() {
        val dir = directory()
        check(File(dir, "prepared").readText() == "synthetic-forget" && File(dir, "phase").readText() == phase())
        val f = open(dir)
        val store = CloudConnectionStore(f.context, File(dir, "connection"))
        controller(f, store, CloudFakePeer(f.scope)) { point ->
            if (point == phase()) {
                assertFalse(store.read()?.enabled == true)
                writeSync(File(dir, "inside-transaction"), "ready-for-host-force-stop")
                Thread.sleep(60_000)
                error("Host did not terminate the isolated Forget probe")
            }
        }.forget()
        error("Expected interruption")
    }

    @Test fun verifyRollbackAfterActualProcessDeath() {
        val dir = directory()
        check(File(dir, "inside-transaction").readText() == "ready-for-host-force-stop")
        val f = open(dir)
        val store = CloudConnectionStore(f.context, File(dir, "connection"))
        assertFalse(store.read()?.enabled == true)
        val peer = CloudFakePeer(f.scope).also { it.fault = "offline" }
        assertEquals(File(dir, "definitions").readText(), f.repo.definitions().toString())
        assertEquals(File(dir, "native-digest").readText(), CloudRoomContentDigest.withoutCloudWitness(f.db.openHelper.readableDatabase))
        controller(f, store, peer).forget()
        assertNull(store.read())
        assertNull(f.db.cloudApplyReceiptDao().current())
        assertEquals(0, peer.calls)
        f.reopen()
        assertEquals(File(dir, "definitions").readText(), f.repo.definitions().toString())
        assertEquals(File(dir, "native-digest").readText(), CloudRoomContentDigest.withoutCloudWitness(f.db.openHelper.readableDatabase))
        writeSync(File(dir, "verified"), "forget-preserved-local-content")
    }
}
