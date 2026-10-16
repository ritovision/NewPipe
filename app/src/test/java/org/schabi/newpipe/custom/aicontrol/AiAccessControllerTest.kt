/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAccessControllerTest {
    private val request = AiTestFixtures.request()

    private fun enabled(scopes: Set<AiScope> = AiScope.entries.toSet()) = AiAccessController().also {
        it.enableFromLocalConsent(request.deviceId, request.catalogEpoch, mapOf(request.clientId to scopes))
    }

    @Test
    fun disabledByDefault() {
        AiTestFixtures.failure("NOT_AUTHORIZED") { AiAccessController().openAuthenticatedSession(request.clientId) }
    }

    @Test
    fun onlyExplicitlyGrantedClientsCanOpenSessions() {
        AiTestFixtures.failure("NOT_AUTHORIZED") { enabled().openAuthenticatedSession("not-paired") }
    }

    @Test
    fun validSessionAndTargetCanRead() {
        val controller = enabled()
        val session = controller.openAuthenticatedSession(request.clientId)
        assertEquals(42, controller.withAuthorization(request, session) { 42 })
    }

    @Test
    fun wrongDeviceIsRejectedEvenWithFullPermissions() {
        val controller = enabled()
        val session = controller.openAuthenticatedSession(request.clientId)
        AiTestFixtures.failure("WRONG_DEVICE") { controller.withAuthorization(request.copy(deviceId = AiTestFixtures.OTHER), session) {} }
    }

    @Test
    fun staleCatalogEpochIsRejected() {
        val controller = enabled()
        val session = controller.openAuthenticatedSession(request.clientId)
        AiTestFixtures.failure("STALE_CATALOG") { controller.withAuthorization(request.copy(catalogEpoch = AiTestFixtures.OTHER), session) {} }
    }

    @Test
    fun claimedClientCannotOverrideAuthenticatedClient() {
        val controller = enabled()
        val session = controller.openAuthenticatedSession(request.clientId)
        AiTestFixtures.failure("WRONG_CLIENT") { controller.withAuthorization(request.copy(clientId = "another"), session) {} }
    }

    @Test
    fun readOnlyGrantCannotDelete() {
        val controller = enabled(setOf(AiScope.LIBRARY_READ))
        val session = controller.openAuthenticatedSession(request.clientId)
        AiTestFixtures.failure("PERMISSION_DENIED") {
            controller.withAuthorization(request.copy(command = AiCatalogCommand.RemoveLibrary(listOf(AiTestFixtures.item))), session) {}
        }
    }

    @Test
    fun deletionGrantExecutesWithoutAConfirmationToken() {
        val controller = enabled(setOf(AiScope.LIBRARY_DELETE))
        val session = controller.openAuthenticatedSession(request.clientId)
        var executed = false
        controller.withAuthorization(request.copy(command = AiCatalogCommand.RemoveLibrary(listOf(AiTestFixtures.item))), session) { executed = true }
        assertTrue(executed)
    }

    @Test
    fun disconnectCannotBeUndoneByReusingTheSession() {
        val controller = enabled()
        val session = controller.openAuthenticatedSession(request.clientId)
        session.disconnect()
        AiTestFixtures.failure("SESSION_CLOSED") { controller.withAuthorization(request, session) {} }
        val newSession = controller.openAuthenticatedSession(request.clientId)
        controller.withAuthorization(request, newSession) { it.checkActive() }
    }

    @Test
    fun disconnectIsVisibleToAnAlreadyExecutingLease() {
        val controller = enabled()
        val session = controller.openAuthenticatedSession(request.clientId)
        controller.withAuthorization(request, session) { lease ->
            session.disconnect()
            AiTestFixtures.failure("SESSION_CLOSED") { lease.checkActive() }
        }
    }

    @Test
    fun disableClosesExistingSessions() {
        val controller = enabled()
        val session = controller.openAuthenticatedSession(request.clientId)
        controller.disable()
        AiTestFixtures.failure("SESSION_CLOSED") { controller.withAuthorization(request, session) {} }
    }

    @Test
    fun reenableCannotReviveAnOldQueuedCommand() {
        val controller = enabled()
        val session = controller.openAuthenticatedSession(request.clientId)
        controller.disable()
        controller.enableFromLocalConsent(request.deviceId, request.catalogEpoch, mapOf(request.clientId to AiScope.entries.toSet()))
        AiTestFixtures.failure("SESSION_CLOSED") { controller.withAuthorization(request, session) {} }
    }

    @Test
    fun sessionFromAnotherControllerIsRejected() {
        val first = enabled()
        val second = enabled()
        AiTestFixtures.failure("SESSION_CLOSED") { second.withAuthorization(request, first.openAuthenticatedSession(request.clientId)) {} }
    }

    @Test
    fun mutationOfOriginalGrantsDoesNotElevatePermissions() {
        val scopes = mutableSetOf(AiScope.LIBRARY_READ)
        val grantMap = mutableMapOf(request.clientId to scopes as Set<AiScope>)
        val controller = AiAccessController()
        controller.enableFromLocalConsent(request.deviceId, request.catalogEpoch, grantMap)
        scopes.add(AiScope.LIBRARY_DELETE)
        grantMap["new-client"] = AiScope.entries.toSet()
        val session = controller.openAuthenticatedSession(request.clientId)
        AiTestFixtures.failure("PERMISSION_DENIED") {
            controller.withAuthorization(request.copy(command = AiCatalogCommand.RemoveLibrary(listOf(AiTestFixtures.item))), session) {}
        }
        AiTestFixtures.failure("NOT_AUTHORIZED") { controller.openAuthenticatedSession("new-client") }
    }

    @Test
    fun receiptLookupMustStillCheckTheOriginalScopes() {
        val controller = enabled(setOf(AiScope.LIBRARY_READ))
        val session = controller.openAuthenticatedSession(request.clientId)
        controller.withAuthorization(request.copy(command = AiCatalogCommand.Receipt(AiTestFixtures.OTHER)), session) { lease ->
            AiTestFixtures.failure("PERMISSION_DENIED") { lease.requireScopes(setOf(AiScope.LIBRARY_DELETE)) }
        }
    }
}
