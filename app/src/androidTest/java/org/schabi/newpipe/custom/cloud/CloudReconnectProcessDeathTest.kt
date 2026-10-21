/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.grack.nanojson.JsonWriter
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.custom.tagging.TagRuntime

/** Host-driven process death at each durable reconnection boundary; synthetic stores only. */
class CloudReconnectProcessDeathTest {
    private var fixture: CloudApplyFixture? = null

    private fun directory(): File {
        val token = InstrumentationRegistry.getArguments().getString("cloudProbe")
        assumeTrue("Requires the explicit host driver", token != null)
        require(token!!.matches(Regex("[a-z0-9-]{1,48}")))
        return File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "cloud-reconnect-probe-$token").also { it.mkdirs() }
    }

    private fun phase(): String = checkNotNull(InstrumentationRegistry.getArguments().getString("cloudPhase")).also {
        require(it in setOf("journal", "archived-outbox", "witness", "metadata", "connection"))
    }

    private fun open(dir: File) = CloudApplyFixture(ApplicationProvider.getApplicationContext(), dir.toPath()).also { fixture = it }
    private fun writeSync(file: File, text: String) {
        FileOutputStream(file).use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
    }
    private fun ids(snapshot: CloudNativeSnapshot) = snapshot.nativeIds.entries
        .map { "${it.key.kind}:${it.key.id}=${it.value}" }.sorted().joinToString("\n")

    private fun controller(f: CloudApplyFixture, connection: CloudConnectionStore, peer: CloudFakePeer, checkpoint: ((String) -> Unit)? = null) = CloudSyncController(f.context, connection, { f.repo }, peer::client, { _: CloudNativeSnapshot -> }, checkpoint)

    @After fun close() {
        fixture?.close()
    }

    @Test fun prepareCommittedIdentities() {
        val dir = directory()
        check(!File(dir, "prepared").exists())
        val f = open(dir)
        f.initialize()
        val first = f.seed()
        val peer = CloudFakePeer(f.scope)
        peer.external(f.target(first))
        val connection = CloudConnectionStore(f.context, File(dir, "connection"))
        val c = controller(f, connection, peer)
        c.connect("https://example.test", peer.tokenA)
        c.confirm(c.prepare(true))
        assertNotNull(f.repo.library.cloudApplyReceiptDao().current())
        f.media("ProcessDeathPendingReconnect")
        peer.fault = "beforeStart"
        try {
            c.syncNow()
            error("Expected a preserved pending operation")
        } catch (_: CloudRemoteException) {
            assertTrue(c.hasPending())
        }
        val before = f.capture()
        writeSync(File(dir, "before-fingerprint"), before.fingerprint)
        writeSync(File(dir, "before-ids"), ids(before))
        writeSync(File(dir, "before-incarnation"), connection.read()!!.incarnation)
        writeSync(File(dir, "old-outbox"), File(connection.directory, "outbox/manifest.json").readText())
        for (name in listOf("remote-recovery", "remote-replacement")) {
            File(connection.directory, "$name/pending.json").apply {
                parentFile!!.mkdirs()
                writeText("opaque old $name work")
            }
        }
        writeSync(File(dir, "peer-catalog"), JsonWriter.string().`object`(CloudApplyPlanJson.catalog(peer.catalog())).done())
        writeSync(File(dir, "phase"), phase())
        writeSync(File(dir, "prepared"), "synthetic-reconnection")
    }

    @Test fun holdChangedIdentityTransactionForHostKill() {
        val dir = directory()
        check(File(dir, "prepared").readText() == "synthetic-reconnection")
        check(File(dir, "phase").readText() == phase())
        val f = open(dir)
        f.initialize()
        val connection = CloudConnectionStore(f.context, File(dir, "connection"))
        val peer = CloudFakePeer(CloudIdentityStore.Scope("recovered-service", f.scope.library))
        peer.external(CloudCatalogCodec.read(File(dir, "peer-catalog").readText()))
        controller(f, connection, peer) { point ->
            if (point == phase()) {
                check(connection.reconnectPending())
                check(!connection.read()!!.enabled)
                writeSync(File(dir, "inside-transaction"), "ready-for-host-force-stop")
                Thread.sleep(60_000)
                error("Host did not terminate the isolated reconnection process")
            }
        }.reconnect(peer.tokenB)
        error("Expected the host to interrupt reconnection")
    }

    @Test fun verifyRollbackAfterActualProcessDeath() {
        val dir = directory()
        check(File(dir, "inside-transaction").readText() == "ready-for-host-force-stop")
        check(File(dir, "phase").readText() == phase())
        val f = open(dir)
        val connection = CloudConnectionStore(f.context, File(dir, "connection"))
        assertTrue(connection.reconnectPending())
        assertFalse(connection.read()!!.enabled)
        val peer = CloudFakePeer(CloudIdentityStore.Scope("recovered-service", f.scope.library))
        peer.fault = "offline"
        val c = controller(f, connection, peer)
        TagRuntime.lock.withLock { CloudNativeApply.recoverLocked(f.repo) }
        val calls = peer.calls
        val state = c.resumeReconnect()
        assertEquals(calls, peer.calls)
        assertFalse(state.enabled)
        assertFalse(connection.reconnectPending())
        assertFalse(c.hasPending())
        assertEquals("recovered-service", state.scope.service)
        assertEquals(File(dir, "before-incarnation").readText(), state.incarnation)
        val after = CloudNativeCatalog(f.repo).capture(state.scope, state.incarnation)
        assertEquals(File(dir, "before-fingerprint").readText(), after.fingerprint)
        assertEquals(File(dir, "before-ids").readText(), ids(after))
        val retired = File(connection.directory, "retired-connections/${state.generation}")
        assertEquals(File(dir, "old-outbox").readText(), File(retired, "outbox/manifest.json").readText())
        for (name in listOf("remote-recovery", "remote-replacement")) {
            assertEquals("opaque old $name work", File(retired, "$name/pending.json").readText())
        }
        assertNull(f.repo.library.cloudApplyReceiptDao().current())
        assertNull(CloudApplyStore.baseline(f.repo.store.readableDatabase))
        assertEquals(peer.tokenB, connection.token(state))
        f.reopen()
        val reopened = CloudConnectionStore(f.context, connection.directory)
        assertFalse(reopened.read()!!.enabled)
        assertFalse(reopened.reconnectPending())
        val persisted = CloudNativeCatalog(f.repo).capture(state.scope, state.incarnation)
        assertEquals(after.fingerprint, persisted.fingerprint)
        assertEquals(after.nativeIds, persisted.nativeIds)
        writeSync(File(dir, "verified"), "reconnect-preserved-local-content")
    }
}
