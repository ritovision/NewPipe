/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.schabi.newpipe.custom.cloud

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.custom.tagging.NativeCatalogStaging
import org.schabi.newpipe.custom.tagging.TagBackupCoordinator
import org.schabi.newpipe.streams.io.StoredFileHelper

class CloudStagingProcessDeathTest {
    private var fixture: CloudApplyFixture? = null
    private fun directory(): File {
        val token = InstrumentationRegistry.getArguments().getString("cloudProbe")
        assumeTrue("Explicit host driver required", token != null)
        require(token!!.matches(Regex("[a-z0-9-]{1,48}")))
        return File(ApplicationProvider.getApplicationContext<Context>().noBackupFilesDir, "cloud-staging-probe-$token").also { it.mkdirs() }
    }
    private fun open(dir: File) = CloudApplyFixture(ApplicationProvider.getApplicationContext(), dir.toPath()).also { fixture = it }
    private fun write(file: File, text: String) = FileOutputStream(file).use {
        it.write(text.toByteArray())
        it.fd.sync()
    }
    private fun zip(f: CloudApplyFixture) = StoredFileHelper(f.context, null, Uri.fromFile(f.dir.resolve("retained-native.zip").toFile()), "synthetic-native-staging")

    @After fun close() {
        fixture?.close()
    }

    @Test fun prepareCommittedIdentities() {
        val dir = directory()
        check(!File(dir, "prepared").exists())
        val f = open(dir)
        f.initialize()
        f.seed()
        Files.createFile(f.dir.resolve("retained-native.zip"))
        TagBackupCoordinator.export(f.context, f.context.getSharedPreferences("synthetic-staging", Context.MODE_PRIVATE), zip(f))
        val safety = File(f.context.noBackupFilesDir, "cloud-sync/recovery").also { it.mkdirs() }
        write(File(safety, "before-last-cloud-restore.zip"), "not-an-orphan")
        write(File(dir, "definitions"), f.repo.definitions().toString())
        write(File(dir, "native-digest"), CloudRoomContentDigest.withoutCloudWitness(f.db.openHelper.readableDatabase))
        write(File(dir, "prepared"), "synthetic-staging")
    }

    @Test fun holdChangedIdentityTransactionForHostKill() {
        val dir = directory()
        check(File(dir, "prepared").readText() == "synthetic-staging")
        val f = open(dir)
        val prepared = TagBackupCoordinator.prepare(f.context, zip(f))
        val export = NativeCatalogStaging.create(f.context, "export")
        Files.write(export.resolve("validated-temporary-snapshot"), byteArrayOf(1, 2, 3))
        NativeCatalogStaging.initialize(f.context)
        assertTrue(Files.isDirectory(prepared.directory))
        assertTrue(Files.isDirectory(export))
        write(File(dir, "stages"), prepared.directory.fileName.toString() + "\n" + export.fileName.toString())
        write(File(dir, "inside-transaction"), "ready-for-host-force-stop")
        Thread.sleep(60_000)
        error("Host must stop this synthetic staging owner")
    }

    @Test fun verifyRollbackAfterActualProcessDeath() {
        val dir = directory()
        val f = open(dir)
        val root = f.context.noBackupFilesDir.toPath()
        val old = File(dir, "stages").readLines()
        assertTrue(old.all { Files.isDirectory(root.resolve(it)) })
        NativeCatalogStaging.initialize(f.context)
        assertTrue(old.none { Files.exists(root.resolve(it)) })
        assertEquals("not-an-orphan", File(root.toFile(), "cloud-sync/recovery/before-last-cloud-restore.zip").readText())
        assertEquals(File(dir, "native-digest").readText(), CloudRoomContentDigest.withoutCloudWitness(f.db.openHelper.readableDatabase))
        TagBackupCoordinator.prepare(f.context, zip(f)).use { prepared ->
            NativeCatalogStaging.initialize(f.context)
            assertTrue(Files.isDirectory(prepared.directory))
            TagBackupCoordinator.installWithShutdown(f.context, prepared) {}
        }
        f.reopen()
        assertEquals(File(dir, "definitions").readText(), f.repo.definitions().toString())
        assertEquals("not-an-orphan", File(root.toFile(), "cloud-sync/recovery/before-last-cloud-restore.zip").readText())
        write(File(dir, "verified"), "staging-orphans-cleaned-active-recovery-preserved")
    }
}
