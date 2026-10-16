/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import java.io.ByteArrayInputStream
import java.net.InetAddress
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiIntegrationPolicyTest {
    private val client = "c_" + "a".repeat(64)
    private val connection = AiTestFixtures.OTHER
    private val owner = "50000000-0000-0000-0000-000000000001"
    private fun local() = AiLocalState(deviceName = "Synthetic test phone")
    private fun frame() = buildJsonObject {
        put("type", "command")
        put("connection_id", connection)
        put("owner_id", owner)
        put("client_id", client)
        put("client_name", "Test AI")
        put("scopes", JsonArray(listOf("mcp.connect", "library.read").map(::JsonPrimitive)))
        put("request", AiProtocol.json.encodeToJsonElement(AiTestFixtures.request().copy(clientId = client)))
    }

    @Test fun gatewayCeilingCannotBeRaisedByOauthScopes() {
        val gate = AiAccessController()
        gate.enableGatewayFromLocalConsent(AiTestFixtures.DEVICE, AiTestFixtures.EPOCH, setOf(AiScope.LIBRARY_READ))
        val session = gate.openAuthenticatedGatewaySession(client, AiScope.entries.toSet())
        gate.withAuthorization(AiTestFixtures.request().copy(clientId = client), session) { it.checkActive() }
        AiTestFixtures.failure("PERMISSION_DENIED") {
            gate.withAuthorization(AiTestFixtures.request(AiCatalogCommand.RemoveLibrary(listOf(AiTestFixtures.item))).copy(clientId = client), session) {}
        }
    }

    @Test fun oauthScopeCannotBeRaisedByFullLocalPermissions() {
        val gate = AiAccessController()
        gate.enableGatewayFromLocalConsent(AiTestFixtures.DEVICE, AiTestFixtures.EPOCH, AiScope.entries.toSet())
        val session = gate.openAuthenticatedGatewaySession(client, setOf(AiScope.LIBRARY_READ))
        AiTestFixtures.failure("PERMISSION_DENIED") {
            gate.withAuthorization(AiTestFixtures.request(AiCatalogCommand.CreatePlaylist("Not allowed")).copy(clientId = client), session) {}
        }
    }

    @Test fun stopSignalsAnExecutingLeaseWithoutWaitingForItsReaderLock() {
        val gate = AiAccessController()
        gate.enableGatewayFromLocalConsent(AiTestFixtures.DEVICE, AiTestFixtures.EPOCH, AiScope.entries.toSet())
        val session = gate.openAuthenticatedGatewaySession(client, AiScope.entries.toSet())
        gate.withAuthorization(AiTestFixtures.request().copy(clientId = client), session) { lease ->
            gate.signalStop()
            AiTestFixtures.failure("SESSION_CLOSED") { lease.checkActive() }
        }
    }

    @Test fun localPolicyReloadDoesNotReviveOldGatewaySessions() {
        val gate = AiAccessController()
        gate.enableGatewayFromLocalConsent(AiTestFixtures.DEVICE, AiTestFixtures.EPOCH, AiScope.entries.toSet())
        val old = gate.openAuthenticatedGatewaySession(client, AiScope.entries.toSet())
        gate.signalStop()
        gate.enableGatewayFromLocalConsent(AiTestFixtures.DEVICE, AiTestFixtures.EPOCH, AiScope.entries.toSet())
        AiTestFixtures.failure("SESSION_CLOSED") { gate.withAuthorization(AiTestFixtures.request().copy(clientId = client), old) {} }
    }

    @Test fun gatewayRequiresAValidatedClientIdentifier() {
        val gate = AiAccessController()
        gate.enableGatewayFromLocalConsent(AiTestFixtures.DEVICE, AiTestFixtures.EPOCH, AiScope.entries.toSet())
        AiTestFixtures.failure("NOT_AUTHORIZED") { gate.openAuthenticatedGatewaySession("arbitrary", AiScope.entries.toSet()) }
    }

    @Test fun unpairedStateCannotBeEnabled() {
        AiTestFixtures.failure("NOT_PAIRED") { AiLocalStatePolicy.validate(local().copy(enabled = true)) }
    }

    @Test fun pairedStateRequiresOwnerAndDeviceFingerprint() {
        AiTestFixtures.failure("INVALID_IDENTIFIER") { AiLocalStatePolicy.validate(local().copy(paired = true)) }
        val valid = local().copy(paired = true, enabled = true, ownerId = owner, gatewayOrigin = "https://control.example.org", keyFingerprint = "a".repeat(64))
        assertEquals(valid, AiLocalStatePolicy.validate(valid))
    }

    @Test fun gatewayAddressCannotContainCredentialsPathOrInsecureScheme() {
        for (url in listOf("http://example.org", "https://user:password@example.org", "https://example.org/path", "https://example.org?token=x", "https://example.org:8443")) {
            AiTestFixtures.failure("INVALID_GATEWAY") { AiLocalStatePolicy.origin(url) }
        }
        assertEquals("https://example.org", AiLocalStatePolicy.origin("https://EXAMPLE.org/"))
    }

    @Test fun unknownLocalPermissionFailsClosed() {
        AiTestFixtures.failure("INVALID_LOCAL_STATE") { AiLocalStatePolicy.validate(local().copy(permissions = setOf("run.shell"))) }
    }

    @Test fun matchingRelayFrameRetainsOnlyKnownCapabilities() {
        val decoded = AiRelayWire.incoming(frame(), connection, owner, AiTestFixtures.DEVICE)
        assertEquals(client, decoded.clientId)
        assertEquals(setOf(AiScope.LIBRARY_READ), decoded.scopes)
        assertEquals(AiTestFixtures.REQUEST, decoded.requestId)
    }

    @Test fun relayCannotChangeOwnerConnectionOrSelectedDevice() {
        AiTestFixtures.failure("WRONG_RELAY_SESSION") { AiRelayWire.incoming(frame(), "wrong", owner, AiTestFixtures.DEVICE) }
        AiTestFixtures.failure("WRONG_RELAY_SESSION") { AiRelayWire.incoming(frame(), connection, "wrong", AiTestFixtures.DEVICE) }
        AiTestFixtures.failure("WRONG_RELAY_TARGET") { AiRelayWire.incoming(frame(), connection, owner, AiTestFixtures.OTHER) }
    }

    @Test fun relaySizeDepthAndExtraFieldsAreRejected() {
        AiTestFixtures.failure("RELAY_PAYLOAD_TOO_LARGE") { AiRelayWire.parse("x".repeat(AiRelayWire.MAX_FRAME_BYTES + 1)) }
        AiTestFixtures.failure("INVALID_RELAY_MESSAGE") { AiRelayWire.parse("[".repeat(20) + "]".repeat(20)) }
        val extra = buildJsonObject {
            frame().forEach { (k, v) -> put(k, v) }
            put("sql", "DELETE")
        }
        AiTestFixtures.failure("INVALID_RELAY_MESSAGE") { AiRelayWire.incoming(extra, connection, owner, AiTestFixtures.DEVICE) }
        AiTestFixtures.failure("RELAY_PAYLOAD_TOO_LARGE") { AiRelayWire.read(ByteArrayInputStream(ByteArray(20)), 10) }
    }

    @Test fun publicAddressPolicyBlocksPrivateLoopbackAndTailscaleRanges() {
        for (ip in listOf("127.0.0.1", "10.0.0.1", "192.168.1.1", "172.31.1.1", "169.254.169.254", "100.68.130.31", "0.0.0.0", "224.0.0.1", "::1", "fc00::1", "fe80::1", "2001:db8::1")) {
            assertFalse(ip, AiPublicUrlPolicy.isPublic(InetAddress.getByName(ip)))
        }
        assertTrue(AiPublicUrlPolicy.isPublic(InetAddress.getByName("8.8.8.8")))
        assertTrue(AiPublicUrlPolicy.isPublic(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test fun sourceUrlCannotRequestLocalFilesOrCredentialBearingAddresses() {
        for (url in listOf("file:///private", "https://user:secret@example.org", "https://example.org:1234/x")) {
            AiTestFixtures.failure("INVALID_URL") { AiPublicUrlPolicy.validate(url) }
        }
        for (url in listOf("https://localhost/x", "https://router.local/x", "https://host.internal/x")) {
            AiTestFixtures.failure("PRIVATE_NETWORK_URL") { AiPublicUrlPolicy.validate(url) }
        }
    }

    @Test fun newCommandsRoundTripAndRequireSideEffectPermissions() {
        val commands = listOf(AiCatalogCommand.PlayItems(listOf(AiTestFixtures.item)), AiCatalogCommand.AppendQueue(listOf(AiTestFixtures.item)), AiCatalogCommand.ResolveUrl("https://example.org/a"), AiCatalogCommand.SaveUrls(listOf("https://example.org/a"), 3))
        commands.forEach { command ->
            val request = AiTestFixtures.request(command)
            assertEquals(request, AiProtocol.decode(AiProtocol.json.encodeToString(request)))
        }
        assertEquals(setOf(AiScope.LIBRARY_WRITE, AiScope.PLAYLIST_WRITE), AiProtocol.requiredScopes(commands[3]))
        assertEquals(setOf(AiScope.PLAYBACK_CONTROL, AiScope.LIBRARY_READ), AiProtocol.requiredScopes(commands[0]))
    }

    @Test fun futureClockSkewCannotTurnForgottenPlaybackIntoNotExecuted() {
        var now = AiTestFixtures.NOW
        var effects = 0
        val gate = AiAccessController()
        gate.enableFromLocalConsent(AiTestFixtures.DEVICE, AiTestFixtures.EPOCH, mapOf("test-client" to setOf(AiScope.PLAYBACK_CONTROL)))
        val executor = AiPlaybackExecutor(
            gate,
            AiPlaybackPort { _, _ ->
                effects++
                buildJsonObject {}
            }
        ) { now }
        val request = AiTestFixtures.request(AiCatalogCommand.Pause).copy(issuedAtMs = now + 5000, expiresAtMs = now + 25_000)
        val raw = AiProtocol.json.encodeToString(request)
        val session = gate.openAuthenticatedSession("test-client")
        assertEquals(AiOutcome.COMPLETED, executor.execute(raw, session).outcome)
        now += 181_000
        assertEquals(AiOutcome.OUTCOME_UNKNOWN, executor.execute(raw, session).outcome)
        assertEquals(1, effects)
    }
}
