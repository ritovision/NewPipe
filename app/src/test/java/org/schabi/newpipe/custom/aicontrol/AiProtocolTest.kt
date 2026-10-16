/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

internal object AiTestFixtures {
    const val DEVICE = "10000000-0000-0000-0000-000000000001"
    const val EPOCH = "20000000-0000-0000-0000-000000000001"
    const val REQUEST = "30000000-0000-0000-0000-000000000001"
    const val OTHER = "40000000-0000-0000-0000-000000000001"
    const val NOW = 1_800_000_000_000L
    val item = AiStreamRef(1, "YouTube", "https://www.youtube.com/watch?v=example")

    fun request(command: AiCatalogCommand = AiCatalogCommand.Status) = AiRequest(1, DEVICE, "test-client", EPOCH, REQUEST, NOW, NOW + 30_000, command)

    fun failure(code: String, action: () -> Unit) {
        try {
            action()
            fail("Expected $code")
        } catch (error: AiFailure) {
            assertEquals(code, error.code)
        }
    }
}

class AiProtocolTest {
    private fun encode(request: AiRequest) = AiProtocol.json.encodeToString(request)

    @Test
    fun everyCatalogCommandRoundTrips() {
        val items = listOf(AiTestFixtures.item)
        val revision = "a".repeat(64)
        val commands = listOf(
            AiCatalogCommand.Status,
            AiCatalogCommand.ListPlaylists(),
            AiCatalogCommand.ListItems(2),
            AiCatalogCommand.Search("music"),
            AiCatalogCommand.GetStream(items.single()),
            AiCatalogCommand.CreatePlaylist("Empty"),
            AiCatalogCommand.CreatePlaylist("Filled", items),
            AiCatalogCommand.RenamePlaylist(2, "Renamed"),
            AiCatalogCommand.DeletePlaylist(2),
            AiCatalogCommand.Save(items),
            AiCatalogCommand.Add(2, items),
            AiCatalogCommand.Remove(2, items),
            AiCatalogCommand.Reorder(2, listOf(0), revision),
            AiCatalogCommand.Move(2, 3, items),
            AiCatalogCommand.PreviewRemoval(items),
            AiCatalogCommand.RemoveLibrary(items),
            AiCatalogCommand.Receipt(AiTestFixtures.OTHER)
        )
        commands.forEach { command ->
            val request = AiTestFixtures.request(command)
            assertEquals(request, AiProtocol.decode(encode(request)))
        }
    }

    @Test
    fun targetCannotBeOmittedOrDefaulted() {
        val json = encode(AiTestFixtures.request()).replace("\"device_id\":\"${AiTestFixtures.DEVICE}\",", "")
        AiTestFixtures.failure("INVALID_REQUEST") { AiProtocol.decode(json) }
    }

    @Test
    fun clientCannotSupplyScopes() {
        val json = encode(AiTestFixtures.request()).replaceFirst("{", "{\"scopes\":[\"library.delete\"],")
        AiTestFixtures.failure("INVALID_REQUEST") { AiProtocol.decode(json) }
    }

    @Test
    fun modelCannotSendAuthoritativeStreamMetadata() {
        val json = encode(AiTestFixtures.request(AiCatalogCommand.Save(listOf(AiTestFixtures.item))))
            .replace("\"stream_id\":1", "\"stream_id\":1,\"title\":\"overwrite\"")
        AiTestFixtures.failure("INVALID_REQUEST") { AiProtocol.decode(json) }
    }

    @Test
    fun unknownCommandsFailClosed() {
        val json = encode(AiTestFixtures.request()).replace("get_catalog_status", "run_sql")
        AiTestFixtures.failure("INVALID_REQUEST") { AiProtocol.decode(json) }
    }

    @Test
    fun incompatibleProtocolFailsClosed() {
        AiTestFixtures.failure("UNSUPPORTED_VERSION") { AiProtocol.decode(encode(AiTestFixtures.request().copy(version = 2))) }
    }

    @Test
    fun idsMustBeCanonicalNotShortUuidAliases() {
        AiTestFixtures.failure("INVALID_IDENTIFIER") { AiProtocol.requireUuid("1-1-1-1-1") }
    }

    @Test
    fun idWithUppercaseUuidIsNotAnotherCanonicalIdentity() {
        AiTestFixtures.failure("INVALID_IDENTIFIER") { AiProtocol.requireUuid("AAAAAAAA-0000-0000-0000-000000000001") }
    }

    @Test
    fun unicodePayloadIsBoundedByBytesNotOnlyCharacters() {
        AiTestFixtures.failure("PAYLOAD_TOO_LARGE") { AiProtocol.decode("é".repeat(40_000)) }
    }

    @Test
    fun hugePayloadIsRejectedBeforeParsing() {
        AiTestFixtures.failure("PAYLOAD_TOO_LARGE") { AiProtocol.decode(" ".repeat(AiProtocol.MAX_REQUEST_BYTES + 1)) }
    }

    @Test
    fun nestingIsBoundedBeforeDeserializer() {
        AiTestFixtures.failure("INVALID_REQUEST") { AiProtocol.decode("[".repeat(100) + "]".repeat(100)) }
    }

    @Test
    fun bracesAndEscapesInsideSearchDoNotCountAsNesting() {
        val request = AiTestFixtures.request(AiCatalogCommand.Search("{\"".repeat(30)))
        assertEquals(request, AiProtocol.decode(encode(request)))
    }

    @Test
    fun paginationIsBounded() {
        AiTestFixtures.failure("INVALID_ARGUMENT") { AiProtocol.validateShape(AiTestFixtures.request(AiCatalogCommand.Search(limit = 51))) }
        AiTestFixtures.failure("INVALID_ARGUMENT") { AiProtocol.validateShape(AiTestFixtures.request(AiCatalogCommand.ListPlaylists(limit = 0))) }
        AiTestFixtures.failure("INVALID_ARGUMENT") { AiProtocol.validateShape(AiTestFixtures.request(AiCatalogCommand.ListItems(1, after = -2))) }
    }

    @Test
    fun catalogEditSizeIsBounded() {
        AiTestFixtures.failure("INVALID_ARGUMENT") { AiProtocol.validateShape(AiTestFixtures.request(AiCatalogCommand.RemoveLibrary(List(501) { AiTestFixtures.item }))) }
        AiProtocol.validateShape(AiTestFixtures.request(AiCatalogCommand.RemoveLibrary(List(500) { AiTestFixtures.item })))
    }

    @Test
    fun noImplicitDeleteAllViaEmptyItems() {
        AiTestFixtures.failure("INVALID_ARGUMENT") { AiProtocol.validateShape(AiTestFixtures.request(AiCatalogCommand.RemoveLibrary(emptyList()))) }
    }

    @Test
    fun emptyPlaylistCreationIsAllowed() {
        AiProtocol.validateShape(AiTestFixtures.request(AiCatalogCommand.CreatePlaylist("New")))
    }

    @Test
    fun blankOrControlCharacterPlaylistNamesFail() {
        for (name in listOf("  ", "a\u0000b", "a\nb", "x".repeat(201))) {
            AiTestFixtures.failure("INVALID_ARGUMENT") { AiProtocol.validateShape(AiTestFixtures.request(AiCatalogCommand.CreatePlaylist(name))) }
        }
    }

    @Test
    fun maliciousAndNonWebReferencesFail() {
        for (url in listOf("file:///etc/passwd", "https://user:secret@example.org/x", "javascript:alert(1)", "https:///missing")) {
            AiTestFixtures.failure("INVALID_URL") { AiProtocol.validateShape(AiTestFixtures.request(AiCatalogCommand.Save(listOf(AiTestFixtures.item.copy(url = url))))) }
        }
    }

    @Test
    fun excessiveLifetimesAndOverflowedDeadlinesFail() {
        AiTestFixtures.failure("INVALID_DEADLINE") { AiProtocol.validateShape(AiTestFixtures.request().copy(expiresAtMs = AiTestFixtures.NOW + AiProtocol.MAX_TTL_MS + 1)) }
        AiTestFixtures.failure("INVALID_DEADLINE") { AiProtocol.validateShape(AiTestFixtures.request().copy(issuedAtMs = Long.MAX_VALUE, expiresAtMs = Long.MIN_VALUE)) }
    }

    @Test
    fun staleAndFutureCommandsDoNotExecute() {
        AiTestFixtures.failure("EXPIRED") { AiProtocol.checkDeadline(AiTestFixtures.request(), AiTestFixtures.NOW + 30_000) }
        AiTestFixtures.failure("CLOCK_SKEW") { AiProtocol.checkDeadline(AiTestFixtures.request(), AiTestFixtures.NOW - 6000) }
        AiProtocol.checkDeadline(AiTestFixtures.request(), AiTestFixtures.NOW + 1000)
    }

    @Test
    fun reorderCannotRepeatAnOccurrence() {
        AiTestFixtures.failure("INVALID_ARGUMENT") { AiProtocol.validateShape(AiTestFixtures.request(AiCatalogCommand.Reorder(2, listOf(0, 0), "a".repeat(64)))) }
    }

    @Test
    fun moveMustHaveDifferentEndpoints() {
        AiTestFixtures.failure("INVALID_ARGUMENT") { AiProtocol.validateShape(AiTestFixtures.request(AiCatalogCommand.Move(2, 2, listOf(AiTestFixtures.item)))) }
    }

    @Test
    fun globalDeletionDoesNotAcceptOnlyPlaylistWritePermission() {
        assertEquals(setOf(AiScope.LIBRARY_DELETE), AiProtocol.requiredScopes(AiCatalogCommand.RemoveLibrary(listOf(AiTestFixtures.item))))
        assertEquals(setOf(AiScope.PLAYLIST_DELETE), AiProtocol.requiredScopes(AiCatalogCommand.DeletePlaylist(2)))
    }

    @Test
    fun addingToOrdinaryPlaylistRequiresTheImpliedSavePermission() {
        assertEquals(setOf(AiScope.PLAYLIST_WRITE, AiScope.LIBRARY_WRITE), AiProtocol.requiredScopes(AiCatalogCommand.Add(2, listOf(AiTestFixtures.item))))
        assertEquals(setOf(AiScope.PLAYLIST_WRITE), AiProtocol.requiredScopes(AiCatalogCommand.CreatePlaylist("Empty")))
    }

    @Test
    fun previewCannotBeMistakenForAnExecutableMutation() {
        assertFalse(AiProtocol.isMutation(AiCatalogCommand.PreviewRemoval(listOf(AiTestFixtures.item))))
        assertTrue(AiProtocol.isMutation(AiCatalogCommand.RemoveLibrary(listOf(AiTestFixtures.item))))
    }

    @Test
    fun fingerprintsBindAllEffectfulAndIdentityFields() {
        val original = AiTestFixtures.request(AiCatalogCommand.CreatePlaylist("A"))
        val changed = listOf(
            original.copy(deviceId = AiTestFixtures.OTHER),
            original.copy(clientId = "other-client"),
            original.copy(catalogEpoch = AiTestFixtures.OTHER),
            original.copy(expiresAtMs = original.expiresAtMs + 1),
            original.copy(command = AiCatalogCommand.CreatePlaylist("B"))
        )
        changed.forEach { assertNotEquals(AiProtocol.fingerprint(original), AiProtocol.fingerprint(it)) }
        assertEquals(AiProtocol.fingerprint(original), AiProtocol.fingerprint(AiProtocol.decode(encode(original))))
    }
}
