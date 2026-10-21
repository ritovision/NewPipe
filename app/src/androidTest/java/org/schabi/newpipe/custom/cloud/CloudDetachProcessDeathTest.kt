/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.schabi.newpipe.custom.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit host kill of a synthetic local detach. Never contacts a live service. */
class CloudDetachProcessDeathTest {
    private var fixture: CloudApplyFixture? = null
    private fun directory(): File {
        val token = InstrumentationRegistry.getArguments().getString("cloudProbe")
        assumeTrue("Explicit host driver required", token != null)
        require(token!!.matches(Regex("[a-z0-9-]{1,48}")))
        return File(ApplicationProvider.getApplicationContext<Context>().noBackupFilesDir, "cloud-detach-probe-$token").also { it.mkdirs() }
    }
    private fun phase() = checkNotNull(InstrumentationRegistry.getArguments().getString("cloudPhase")).also {
        require(it in setOf("journal", "archived-outbox", "metadata", "connection"))
    }
    private fun open(dir: File) = CloudApplyFixture(ApplicationProvider.getApplicationContext(), dir.toPath()).also { fixture = it }
    private fun write(file: File, text: String) = FileOutputStream(file).use {
        it.write(text.toByteArray())
        it.fd.sync()
    }
    private fun controller(f: CloudApplyFixture, store: CloudConnectionStore, peer: CloudFakePeer, point: ((String) -> Unit)? = null) = CloudSyncController(f.context, store, { f.repo }, peer::client, { _: CloudNativeSnapshot -> }, detachCheckpoint = point)

    @After fun close() {
        fixture?.close()
    }

    @Test fun prepareCommittedIdentities() {
        val dir = directory()
        check(!File(dir, "prepared").exists())
        val f = open(dir)
        f.initialize()
        f.seed()
        val peer = CloudFakePeer(f.scope)
        val store = CloudConnectionStore(f.context, File(dir, "connection"))
        val c = controller(f, store, peer)
        c.connect("https://example.test", peer.tokenA)
        c.confirm(c.prepare(true))
        f.media("Pending-and-revoked")
        peer.fault = "beforeStart"
        assertThrows(CloudRemoteException::class.java) { c.syncNow() }
        peer.beforeRequest = { throw CloudRemoteException("INVALID_CREDENTIAL", 401) }
        assertThrows(CloudRemoteException::class.java) { c.syncNow() }
        assertTrue(store.read()!!.authorityRejected)
        val files = File(store.directory, "outbox").walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(store.directory).path to CloudApplyStore.hash(it.readBytes())
        }
        write(File(dir, "outbox-digests"), CloudWireJson.encode(files))
        write(File(dir, "native-digest"), CloudRoomContentDigest.withoutCloudWitness(f.db.openHelper.readableDatabase))
        write(File(dir, "definitions"), f.repo.definitions().toString())
        write(File(dir, "phase"), phase())
        write(File(dir, "prepared"), "synthetic-detach")
    }

    @Test fun holdChangedIdentityTransactionForHostKill() {
        val dir = directory()
        check(File(dir, "prepared").readText() == "synthetic-detach" && File(dir, "phase").readText() == phase())
        val f = open(dir)
        val store = CloudConnectionStore(f.context, File(dir, "connection"))
        val peer = CloudFakePeer(f.scope).also { it.beforeRequest = { throw AssertionError("NO_NETWORK_DURING_LOCAL_DETACH") } }
        controller(f, store, peer) { point ->
            if (point == phase()) {
                assertFalse(store.read()?.enabled == true)
                write(File(dir, "inside-transaction"), "ready-for-host-force-stop")
                Thread.sleep(60_000)
                error("Host must stop this synthetic probe")
            }
        }.detachRejectedConnection()
        error("Expected host interruption")
    }

    @Test fun verifyRollbackAfterActualProcessDeath() {
        val dir = directory()
        check(File(dir, "inside-transaction").readText() == "ready-for-host-force-stop")
        val f = open(dir)
        val store = CloudConnectionStore(f.context, File(dir, "connection"))
        val peer = CloudFakePeer(f.scope).also { it.beforeRequest = { throw AssertionError("NO_NETWORK_DURING_LOCAL_DETACH") } }
        controller(f, store, peer).detachRejectedConnection()
        assertNull(store.read())
        assertFalse(store.detachPending())
        val archive = File(store.directory, "retired-connections").listFiles()!!.single()
        val expected = CloudWireJson.obj(File(dir, "outbox-digests").readText())
        for (name in expected.keys) assertEquals(expected.getString(name), CloudApplyStore.hash(File(archive, name).readBytes()))
        assertFalse(File(archive, "connection.json").readText().contains(peer.tokenA))
        assertEquals(File(dir, "native-digest").readText(), CloudRoomContentDigest.withoutCloudWitness(f.db.openHelper.readableDatabase))
        assertEquals(File(dir, "definitions").readText(), f.repo.definitions().toString())
        assertEquals(0, peer.calls)
        peer.beforeRequest = null
        assertEquals("CLOUD_FRESH_DEVICE_REQUIRED", assertThrows(IllegalStateException::class.java) { store.requireFreshDevice("https://example.test", f.scope, "device-a") }.message)
        val joined = controller(f, store, peer).connect("https://example.test", peer.tokenB)
        assertFalse(joined.enabled)
        assertTrue(archive.isDirectory)
        write(File(dir, "verified"), "detach-preserved-native-and-pending-evidence")
    }
}
