/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Path
import java.security.KeyStore
import java.security.Signature
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.schabi.newpipe.custom.library.LibraryBindingStore
import org.schabi.newpipe.custom.library.LibraryRepository
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.testUtil.TestDatabase
import org.schabi.newpipe.testUtil.TrampolineSchedulerRule

/** Isolated QA app, synthetic in-memory catalog. Never imports or changes the user's real Library. */
class AiActivationAndroidTest {
    @get:Rule val trampoline = TrampolineSchedulerRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var access: AiAccessController
    private lateinit var session: AiAccessController.Session
    private lateinit var binding: Path
    private var library = 0L
    private val now = 1_800_000_000_000L
    private val device = "10000000-0000-0000-0000-000000000001"
    private val epoch = "20000000-0000-0000-0000-000000000001"

    @Before fun setup() {
        LibraryRepository.forgetSessionForTest()
        db = TestDatabase.createReplacingNewPipeDatabase()
        binding = context.cacheDir.toPath().resolve("activation-${UUID.randomUUID()}.db")
        library = LibraryRepository.initialize(db, LibraryBindingStore(binding))
        access = AiAccessController().apply { enableFromLocalConsent(device, epoch, mapOf("test" to AiScope.entries.toSet())) }
        session = access.openAuthenticatedSession("test")
    }

    @After fun close() {
        access.disable()
        LibraryRepository.forgetSessionForTest()
        db.close()
        LibraryBindingStore.deleteForDatabase(binding)
    }

    private fun request(command: AiCatalogCommand): String = AiProtocol.json.encodeToString(AiRequest(1, device, "test", epoch, UUID.randomUUID().toString(), now, now + 30_000, command))
    private fun media(url: String, title: String = "Authoritative title") = AiResolvedMedia(
        StreamEntity(serviceId = 0, url = url, title = title, streamType = StreamType.VIDEO_STREAM, duration = 120, uploader = "Synthetic uploader"),
        "service-0"
    )
    private fun engine(resolver: AiUrlResolver) = AiCatalogExecutor(db, access, AiCatalogStore(db, AiServiceNames { "service-$it" }), resolver) { now }
    private fun count(table: String) = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst()
        it.getLong(0)
    }
    private fun saved() = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM playlist_stream_join WHERE playlist_id = $library").use {
        it.moveToFirst()
        it.getLong(0)
    }

    @Test fun resolveIsReadOnlyAndNetworkRunsOutsideRoomTransaction() {
        val executor = engine(
            AiUrlResolver { url, check ->
                assertFalse(db.inTransaction())
                check()
                media(url)
            }
        )
        val result = executor.execute(request(AiCatalogCommand.ResolveUrl("https://example.org/video")), session)
        assertEquals(AiOutcome.COMPLETED, result.outcome)
        assertEquals("Authoritative title", result.data.getValue("title").jsonPrimitive.content)
        assertEquals(0, count("streams"))
        assertEquals(0, saved())
    }

    @Test fun savingUrlsAndReceiptCommitTogether() {
        val executor = engine(
            AiUrlResolver { url, check ->
                assertFalse(db.inTransaction())
                check()
                media(url)
            }
        )
        val result = executor.execute(request(AiCatalogCommand.SaveUrls(listOf("https://example.org/one", "https://example.org/two"))), session)
        assertEquals(AiOutcome.COMMITTED, result.outcome)
        assertEquals(2, result.data.getValue("added_items").jsonPrimitive.long)
        assertEquals(2, saved())
        assertEquals(1, count("rv_ai_command_receipts"))
    }

    @Test fun receiptRetryDoesNotResolveOrSaveAgain() {
        var fetched = 0
        val executor = engine(
            AiUrlResolver { url, _ ->
                fetched++
                if (fetched > 1) throw AiFailure("SOURCE_OFFLINE")
                media(url)
            }
        )
        val raw = request(AiCatalogCommand.SaveUrls(listOf("https://example.org/video")))
        assertEquals(AiOutcome.COMMITTED, executor.execute(raw, session).outcome)
        assertTrue(executor.execute(raw, session).replayed)
        assertEquals(1, fetched)
        assertEquals(1, saved())
    }

    @Test fun failedSecondExtractionLeavesNothingSaved() {
        val executor = engine(
            AiUrlResolver { url, _ ->
                if (url.endsWith("two")) throw AiFailure("URL_EXTRACTION_FAILED", AiOutcome.NOT_EXECUTED)
                media(url)
            }
        )
        val result = executor.execute(request(AiCatalogCommand.SaveUrls(listOf("https://example.org/one", "https://example.org/two"))), session)
        assertEquals(AiOutcome.NOT_EXECUTED, result.outcome)
        assertEquals(0, count("streams"))
        assertEquals(0, saved())
        assertEquals(0, count("rv_ai_command_receipts"))
    }

    @Test fun existingMetadataIsNotOverwrittenWhenSavingResolvedUrlAgain() {
        val first = engine(AiUrlResolver { url, _ -> media(url, "Existing title") })
        assertEquals(AiOutcome.COMMITTED, first.execute(request(AiCatalogCommand.SaveUrls(listOf("https://example.org/video"))), session).outcome)
        val second = engine(AiUrlResolver { url, _ -> media(url, "New title must not replace") })
        assertEquals(AiOutcome.COMMITTED, second.execute(request(AiCatalogCommand.SaveUrls(listOf("https://example.org/video"))), session).outcome)
        val title = db.openHelper.writableDatabase.query("SELECT title FROM streams").use {
            it.moveToFirst()
            it.getString(0)
        }
        assertEquals("Existing title", title)
        assertEquals(1, saved())
    }

    @Test fun receiptInsertFailureRollsBackAllNewStreams() {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_receipt BEFORE INSERT ON rv_ai_command_receipts BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
        val executor = engine(AiUrlResolver { url, _ -> media(url) })
        val result = executor.execute(request(AiCatalogCommand.SaveUrls(listOf("https://example.org/video"))), session)
        assertEquals(AiOutcome.OUTCOME_UNKNOWN, result.outcome)
        assertEquals(0, count("streams"))
        assertEquals(0, saved())
    }

    @Test fun disconnectDuringExtractionPreventsAnyCommit() {
        val executor = engine(
            AiUrlResolver { url, _ ->
                session.disconnect()
                media(url)
            }
        )
        val result = executor.execute(request(AiCatalogCommand.SaveUrls(listOf("https://example.org/video"))), session)
        assertEquals("SESSION_CLOSED", result.code)
        assertEquals(0, saved())
    }

    @Test fun knownBadDestinationCannotCausePartialSave() {
        val executor = engine(AiUrlResolver { url, _ -> media(url) })
        val result = executor.execute(request(AiCatalogCommand.SaveUrls(listOf("https://example.org/video"), 99999)), session)
        assertEquals("PLAYLIST_NOT_FOUND", result.code)
        assertEquals(0, count("streams"))
    }

    @Test fun privateStateIsNotStoredInDefaultPreferencesOrDatabaseDirectory() {
        val name = "qa-state-${UUID.randomUUID()}.json"
        val file = java.io.File(context.noBackupFilesDir, name)
        try {
            val store = AiLocalStateStore(context, name)
            val initial = store.read()
            assertFalse(initial.paired)
            assertFalse(initial.enabled)
            assertTrue(file.exists())
            val changed = store.update { it.copy(deviceName = "Synthetic changed") }
            assertEquals(changed, store.read())
            file.writeText("corrupt")
            try {
                store.read()
                throw AssertionError("Corrupt state was accepted")
            } catch (error: AiFailure) {
                assertEquals("INVALID_LOCAL_STATE", error.code)
            }
        } finally {
            file.delete()
            java.io.File(file.path + ".bak").delete()
            java.io.File(file.path + ".new").delete()
        }
    }

    @Test fun keystorePrivateKeySignsWithoutExportingSecret() {
        val id = UUID.randomUUID().toString()
        val keys = AiDeviceKeys()
        val alias = "rv.newpipe.ai.$id"
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val public = keys.create(id)
            assertTrue(public.isNotBlank())
            assertTrue(keys.exists(id))
            val signature = android.util.Base64.decode(keys.sign(id, "Synthetic proof"), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
            val verifier = Signature.getInstance("SHA256withECDSA").apply {
                initVerify(keystore.getCertificate(alias))
                update("Synthetic proof".toByteArray())
            }
            assertTrue(verifier.verify(signature))
            assertEquals(null, keystore.getKey(alias, null).encoded)
        } finally {
            keystore.deleteEntry(alias)
        }
    }

    @Test fun controlActivityAndServiceAreNotExported() {
        @Suppress("DEPRECATION")
        val service = context.packageManager.getServiceInfo(ComponentName(context, AiControlService::class.java), 0)

        @Suppress("DEPRECATION")
        val activity = context.packageManager.getActivityInfo(ComponentName(context, AiControlActivity::class.java), 0)
        assertFalse(service.exported)
        assertFalse(activity.exported)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.packageManager.checkPermission("android.permission.FOREGROUND_SERVICE", context.packageName))
    }
}
