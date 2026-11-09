/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiBrowserPairingPolicyTest {
    private val now = 1_800_000_000_000L
    private val owner = "50000000-0000-0000-0000-000000000001"
    private val device = "10000000-0000-0000-0000-000000000001"
    private val epoch = "20000000-0000-0000-0000-000000000001"
    private fun pending(): AiBrowserPairingPending {
        val base = AiLocalState(deviceId = device, catalogEpoch = epoch, deviceName = "Test phone")
        return AiBrowserPairingPending(
            base = base,
            proposed = base.copy(gatewayOrigin = "https://gateway.test", permissions = setOf("playback.control", "library.read")),
            publicKey = "A".repeat(120),
            clientNonce = "B".repeat(43),
            issuedAtMs = now,
            nonceReady = true
        )
    }
    private fun response(value: AiBrowserPairingPending = pending(), status: String = "pending"): JsonObject = buildJsonObject {
        put("pairing_version", 1)
        put("protocol_version", 1)
        put("request_id", value.requestId)
        put("status", status)
        put("paired", status == "complete")
        put("device_id", value.proposed.deviceId)
        put("owner_id", owner)
        put("client_nonce", value.clientNonce)
        put("public_key_hash", AiProtocol.digest(value.publicKey))
        put("name", value.proposed.deviceName)
        put("permissions", JsonArray(value.proposed.permissions.sorted().map(::JsonPrimitive)))
        put("issued_at_ms", value.issuedAtMs)
        put("expires_at_ms", now + 300_000)
        put("comparison_code", value.comparisonCode)
    }
    private fun changed(json: JsonObject, key: String, value: kotlinx.serialization.json.JsonElement) = JsonObject(json + (key to value))
    private fun rejected(action: () -> Unit) {
        try {
            action()
            throw AssertionError("Expected a rejected pairing response")
        } catch (_: AiFailure) { }
    }

    @Test fun transcriptUsesSortedPermissionsAndExplicitOperationAndOrigin() {
        val p = pending()
        val expected = listOf(
            "newpipe-pairing-v1", "begin", "https://gateway.test", device, "A".repeat(120),
            "B".repeat(43), now.toString(), "Test phone", "library.read,playback.control", ""
        ).joinToString("\n")
        assertEquals(expected, p.message("begin"))
        assertEquals(AiProtocol.digest(expected), p.requestId)
        assertNotEquals(p.message("begin"), p.message("finish"))
        assertTrue(p.message("status").endsWith("\n" + p.requestId))
        assertNotEquals(p.requestId, p.copy(proposed = p.proposed.copy(gatewayOrigin = "https://other.test")).requestId)
    }

    @Test fun requestIncludesImmutableDetailsAndNoOwnerKeyOrPasskeyMaterial() {
        val p = pending()
        val request = p.request("status", "synthetic-signature")
        assertEquals(JsonPrimitive(p.requestId), request["request_id"])
        assertEquals(JsonArray(listOf("library.read", "playback.control").map(::JsonPrimitive)), request["permissions"])
        assertFalse(request.containsKey("owner_key"))
        assertFalse(request.containsKey("passkey"))
        assertFalse(p.request("begin", "synthetic-signature").containsKey("request_id"))
        assertEquals("https://gateway.test/pair/device?request=" + p.requestId, p.approvalUrl)
    }

    @Test fun initialResponsePinsOwnerAndDeadlineWithoutMutatingActiveState() {
        val p = pending()
        val accepted = AiBrowserPairingPolicy.accept(p, response(p))
        assertEquals(p.base, accepted.base)
        assertFalse(accepted.base.paired)
        assertFalse(accepted.base.enabled)
        assertEquals(owner, accepted.ownerId)
        assertEquals(now + 300_000, accepted.expiresAtMs)
        assertEquals(p.comparisonCode, accepted.comparisonCode)
    }

    @Test fun completionIsImpossibleWithoutPersistedFinishIntent() {
        val p = pending()
        rejected { AiBrowserPairingPolicy.accept(p, response(p, "complete")) }
        val approved = AiBrowserPairingPolicy.accept(p, response(p, "approved"))
        rejected { AiBrowserPairingPolicy.completedState(approved) }
        val submitted = approved.copy(finishSubmitted = true)
        val complete = AiBrowserPairingPolicy.accept(submitted, response(submitted, "complete"))
        val active = AiBrowserPairingPolicy.completedState(complete)
        assertTrue(active.paired)
        assertFalse(active.enabled)
        assertEquals(device, active.deviceId)
        assertEquals(epoch, active.catalogEpoch)
        assertEquals(p.proposed.permissions, active.permissions)
        assertEquals(owner, active.ownerId)
        assertEquals(AiProtocol.digest(p.publicKey), active.keyFingerprint)
    }

    @Test fun statusCannotBecomeACommandOrPermissionEscalation() {
        val p = pending()
        val valid = response(p)
        for (status in listOf("enabled", "execute", "local", "unknown")) {
            rejected {
                AiBrowserPairingPolicy.accept(p, changed(valid, "status", JsonPrimitive(status)))
            }
        }
        rejected { AiBrowserPairingPolicy.accept(p, changed(valid, "permissions", JsonArray(listOf("library.delete").map(::JsonPrimitive)))) }
        rejected { AiBrowserPairingPolicy.accept(p, changed(valid, "permissions", JsonArray(p.proposed.permissions.map(::JsonPrimitive)))) }
        rejected { AiBrowserPairingPolicy.accept(p, changed(valid, "paired", JsonPrimitive(true))) }
    }

    @Test fun everyResponseBindingIsVerified() {
        val p = pending()
        val valid = response(p)
        for (key in listOf("request_id", "device_id", "client_nonce", "public_key_hash", "name", "comparison_code")) {
            rejected { AiBrowserPairingPolicy.accept(p, changed(valid, key, JsonPrimitive("wrong"))) }
        }
        for (key in listOf("pairing_version", "protocol_version", "issued_at_ms")) {
            rejected { AiBrowserPairingPolicy.accept(p, changed(valid, key, JsonPrimitive(99))) }
            rejected { AiBrowserPairingPolicy.accept(p, changed(valid, key, JsonPrimitive("1"))) }
        }
        rejected { AiBrowserPairingPolicy.accept(p, JsonObject(valid - "client_nonce")) }
        rejected { AiBrowserPairingPolicy.accept(p, changed(valid, "unexpected", JsonPrimitive("ignored?"))) }
    }

    @Test fun ownerAndExpiryCannotChangeAfterFirstResponse() {
        val p = AiBrowserPairingPolicy.accept(pending(), response())
        rejected { AiBrowserPairingPolicy.accept(p, changed(response(p), "owner_id", JsonPrimitive(device))) }
        rejected { AiBrowserPairingPolicy.accept(p, changed(response(p), "expires_at_ms", JsonPrimitive(now + 310_000))) }
    }

    @Test fun initialDeadlineCannotBeArbitrarilyExtended() {
        for (expiry in listOf(now + 1, now + 239_999, now + 360_001, Long.MAX_VALUE)) {
            rejected { AiBrowserPairingPolicy.accept(pending(), changed(response(), "expires_at_ms", JsonPrimitive(expiry))) }
        }
    }

    @Test fun localStateOrRestoreEpochChangeFencesLateResponses() {
        val p = pending()
        assertTrue(AiBrowserPairingPolicy.matches(p, p.base, now))
        for (modified in listOf(
            p.base.copy(catalogEpoch = owner),
            p.base.copy(deviceId = owner),
            p.base.copy(deviceName = "Renamed"),
            p.base.copy(permissions = setOf("library.delete")),
            p.base.copy(gatewayOrigin = "https://other.test"),
            p.base.copy(blockedClients = setOf("c_" + "a".repeat(64)))
        )) {
            assertFalse(AiBrowserPairingPolicy.matches(p, modified, now))
        }
    }

    @Test fun expiryIsBoundedAndOnlyPersistedCompletionIntentGetsRecoveryWindow() {
        val p = AiBrowserPairingPolicy.accept(pending(), response())
        assertTrue(AiBrowserPairingPolicy.matches(p, p.base, now + 299_999))
        assertFalse(AiBrowserPairingPolicy.matches(p, p.base, now + 300_000))
        val recovering = p.copy(serverStatus = "approved", finishSubmitted = true)
        assertTrue(AiBrowserPairingPolicy.matches(recovering, p.base, now + 400_000))
        assertFalse(AiBrowserPairingPolicy.matches(recovering, p.base, recovering.deadlineMs))
    }

    @Test fun durableLocalCancellationCannotResumeEvenIfFileCleanupFailed() {
        val cancelled = pending().copy(cancelledLocally = true)
        val restored = AiProtocol.json.decodeFromString<AiBrowserPairingPending>(AiProtocol.json.encodeToString(cancelled))
        AiBrowserPairingPolicy.validate(restored)
        assertFalse(AiBrowserPairingPolicy.matches(restored, restored.base, now))
    }

    @Test fun stagingSurvivesSerializationAndNeverEnablesService() {
        val p = AiBrowserPairingPolicy.accept(pending(), response()).copy(serverStatus = "approved", finishSubmitted = true)
        val restored = AiProtocol.json.decodeFromString<AiBrowserPairingPending>(AiProtocol.json.encodeToString(p))
        assertEquals(p, AiBrowserPairingPolicy.validate(restored))
        assertFalse(restored.proposed.enabled)
        assertEquals(p.requestId, restored.requestId)
    }

    @Test fun corruptStagingCannotReplaceActiveIdentityOrEpoch() {
        val p = pending()
        for (bad in listOf(
            p.copy(version = 2),
            p.copy(clientNonce = "short"),
            p.copy(publicKey = "short"),
            p.copy(proposed = p.proposed.copy(deviceId = owner)),
            p.copy(proposed = p.proposed.copy(catalogEpoch = owner)),
            p.copy(serverStatus = "complete"),
            p.copy(serverStatus = "pending")
        )) {
            rejected { AiBrowserPairingPolicy.validate(bad) }
        }
    }

    @Test fun commitRetainsBlocksOnlyWithinTheSameOwner() {
        val block = "c_" + "a".repeat(64)
        val original = pending()
        val sameOwner = original.copy(
            base = original.base.copy(ownerId = owner, blockedClients = setOf(block)),
            proposed = original.proposed.copy(ownerId = owner, blockedClients = setOf(block)),
            finishSubmitted = true
        )
        assertEquals(setOf(block), AiBrowserPairingPolicy.completedState(AiBrowserPairingPolicy.accept(sameOwner, response(sameOwner, "complete"))).blockedClients)
        val otherOwner = sameOwner.copy(base = sameOwner.base.copy(ownerId = device), proposed = sameOwner.proposed.copy(ownerId = device))
        assertTrue(AiBrowserPairingPolicy.completedState(AiBrowserPairingPolicy.accept(otherOwner, response(otherOwner, "complete"))).blockedClients.isEmpty())
    }

    @Test fun pendingDispatchCannotInheritANewerCancellationOrConfigurationGeneration() {
        val p = pending()
        val admission = AiBrowserPairingAdmission(p.requestId, 11, 7)
        assertTrue(admission.matches(p, 11, 7))
        assertFalse(admission.matches(p, 12, 7))
        assertFalse(admission.matches(p, 11, 8))
        assertFalse(admission.matches(null, 11, 7))
        assertFalse(admission.matches(p.copy(clientNonce = "C".repeat(43)), 11, 7))
        assertTrue(admission.matches(AiBrowserPairingPolicy.accept(p, response(p)), 11, 7))
    }

    @Test fun transientHttpFailuresDoNotReadOrParseUntrustedErrorPages() {
        for (status in listOf(408, 429, 500, 502, 503, 504)) {
            val expected = if (status == 429) "RATE_LIMITED" else "GATEWAY_UNAVAILABLE"
            AiTestFixtures.failure(expected) {
                AiBrowserPairingHttp.read(status) { throw AssertionError("Error body must not be parsed") }
            }
        }
    }

    @Test fun malformedSuccessStillFailsWhilePermanentHttpErrorsRemainTerminal() {
        rejected { AiBrowserPairingHttp.read(200) { "<html>not a protocol response</html>" } }
        AiTestFixtures.failure("PAIRING_AUTHORIZATION_ENDED") { AiBrowserPairingHttp.read(401) { "<html>unauthorized</html>" } }
        AiTestFixtures.failure("PAIRING_NOT_SUPPORTED") { AiBrowserPairingHttp.read(404) { "" } }
        AiTestFixtures.failure("PAIRING_EXPIRED_OR_UNKNOWN") { AiBrowserPairingHttp.read(410) { "" } }
        AiTestFixtures.failure("PAIRING_RESTART_REQUIRED") { AiBrowserPairingHttp.read(409) { "{\"error\":\"PAIRING_RESTART_REQUIRED\"}" } }
        assertEquals(response(), AiBrowserPairingHttp.read(200) { response().toString() })
    }

    @Test fun noncePreflightChangesOnlyThePendingNonceAndCannotRepeatAfterAdmission() {
        val p = pending().copy(nonceReady = false)
        val nonce = buildJsonObject {
            put("pairing_version", 1)
            put("client_nonce", "N".repeat(43))
        }
        val ready = AiBrowserPairingPolicy.acceptNonce(p, nonce)
        assertEquals(p.copy(clientNonce = "N".repeat(43), nonceReady = true), ready)
        assertNotEquals(p.requestId, ready.requestId)
        assertEquals(p.base, ready.base)
        val restored = AiProtocol.json.decodeFromString<AiBrowserPairingPending>(AiProtocol.json.encodeToString(ready))
        assertEquals(ready.requestId, restored.requestId)
        rejected { AiBrowserPairingPolicy.acceptNonce(ready, nonce) }
        rejected { AiBrowserPairingPolicy.accept(p, response(p)) }
        rejected { AiBrowserPairingPolicy.acceptNonce(p, changed(nonce, "owner_id", JsonPrimitive(owner))) }
        rejected { AiBrowserPairingPolicy.acceptNonce(p, changed(nonce, "client_nonce", JsonPrimitive("short"))) }
        rejected { AiBrowserPairingPolicy.acceptNonce(p.copy(cancelledLocally = true), nonce) }
    }

    @Test fun cancelledCompletionCannotPublishPairedState() {
        val p = pending().copy(finishSubmitted = true)
        val completed = AiBrowserPairingPolicy.accept(p, response(p, "complete"))
        rejected { AiBrowserPairingPolicy.completedState(completed.copy(cancelledLocally = true)) }
    }

    @Test fun revokedPolicyVersionCannotCommitAnOtherwiseValidResponse() {
        val p = pending().copy(finishSubmitted = true)
        val complete = AiBrowserPairingPolicy.accept(p, response(p, "complete"))
        val gate = AiPolicyGate()
        val version = gate.version
        gate.invalidate()
        var written = false
        assertFalse(
            gate.commit(version, false) {
                AiBrowserPairingPolicy.completedState(complete)
                written = true
            }
        )
        assertFalse(written)
    }
}
