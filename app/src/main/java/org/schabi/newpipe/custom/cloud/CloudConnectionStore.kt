/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.withLock
import org.schabi.newpipe.custom.tagging.TagRuntime

/** Connection and encrypted credentials live in noBackupFilesDir, never exported preferences. */
internal class CloudConnectionStore(context: Context, root: File = File(context.noBackupFilesDir, "cloud-sync")) {
    val directory: File = root
    private val file = AtomicFile(File(root, "connection.json"))
    private val reconnectFile = AtomicFile(File(root, "reconnect.json"))
    private val detachFile = AtomicFile(File(root, "detach.json"))
    private val retiredDevicesFile = AtomicFile(File(root, "retired-devices.json"))
    class Detach(val previous: State, val archive: String)
    class Reconnect(val previous: State, val next: State)
    private val alias = context.packageName + ".cloud." + CloudWireJson.sha256(root.canonicalPath).take(24)
    class State(
        val generation: String,
        val origin: String,
        val device: String,
        val scope: CloudIdentityStore.Scope,
        val incarnation: String,
        val enabled: Boolean,
        internal val encryptedCredential: String,
        val highest: CloudHead?,
        val message: String,
        val lastSuccess: Long,
        val authorityRejected: Boolean = false
    ) {
        override fun toString(): String = "CloudConnection(enabled=$enabled, credential=[redacted])"
        fun copy(
            generation: String = this.generation,
            incarnation: String = this.incarnation,
            enabled: Boolean = this.enabled,
            highest: CloudHead? = this.highest,
            message: String = this.message,
            lastSuccess: Long = this.lastSuccess,
            authorityRejected: Boolean = this.authorityRejected
        ) = State(generation, origin, device, scope, incarnation, enabled, encryptedCredential, highest, message, lastSuccess, authorityRejected)
    }
    fun read(): State? = TagRuntime.lock.withLock {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withLock null
        val bytes = CloudTransferStore.read(file, 65536)
        decode(CloudWireJson.obj(bytes.toString(Charsets.UTF_8)))
    }
    private fun decode(o: com.grack.nanojson.JsonObject): State {
        check(o.getInt("version") == 1) { "UNSUPPORTED_CONNECTION_VERSION" }
        return State(
            o.getString("generation"), o.getString("origin"), o.getString("device"),
            CloudIdentityStore.Scope(o.getString("service"), o.getString("library")), o.getString("incarnation"),
            o.getBoolean("enabled"), o.getString("credential"), o["highest"]?.let(CloudWireJson::head),
            o.getString("message"), CloudCounter.parse(o.getString("lastSuccess")), o["authorityRejected"] == true
        )
    }
    fun create(origin: String, token: String, device: String, scope: CloudIdentityStore.Scope, incarnation: String, head: CloudHead): State = TagRuntime.lock.withLock {
        check(!reconnectPending()) { "CLOUD_RECONNECT_RECOVERY_REQUIRED" }
        check(!detachPending()) { "CLOUD_DETACH_RECOVERY_REQUIRED" }
        check(read() == null) { "FORGET_EXISTING_CONNECTION_FIRST" }
        requireFreshDevice(origin, scope, device)
        check(token.startsWith("$device.") && Regex("[A-Za-z0-9_-]{1,128}\\.[0-9a-f]{64}").matches(token)) { "INVALID_DEVICE_TOKEN" }
        val state = State(UUID.randomUUID().toString(), origin, device, scope, incarnation, false, encrypt(token, origin, device, scope), head, "Connected. Sync is off.", 0)
        save(state)
        state
    }
    fun update(expected: String, transform: (State) -> State): State = TagRuntime.lock.withLock {
        val old = requireCurrent(expected)
        val next = transform(old)
        save(next)
        next
    }
    fun requireCurrent(generation: String, enabled: Boolean = false): State {
        check(!reconnectPending()) { "CLOUD_RECONNECT_RECOVERY_REQUIRED" }
        check(!detachPending()) { "CLOUD_DETACH_RECOVERY_REQUIRED" }
        val s = read()
        check(s != null && s.generation == generation && (!enabled || s.enabled)) { "CLOUD_CONNECTION_CHANGED" }
        return s
    }
    fun disable(message: String = "Sync is off. Local edits stay on this device."): State? = TagRuntime.lock.withLock {
        val s = read() ?: return@withLock null
        if (reconnectPending() || detachPending()) return@withLock s
        val next = s.copy(generation = UUID.randomUUID().toString(), enabled = false, message = message)
        save(next)
        next
    }
    fun forget(expected: String) = TagRuntime.lock.withLock {
        requireCurrent(expected)
        file.delete()
    }
    fun token(state: State): String {
        requireCurrent(state.generation)
        try {
            val pieces = state.encryptedCredential.split(':')
            check(pieces.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret(false), GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
            cipher.updateAAD(aad(state.origin, state.device, state.scope))
            return cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            error("CLOUD_CREDENTIAL_UNAVAILABLE_RECONNECT")
        }
    }
    private fun secret(create: Boolean = true): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "CLOUD_KEY_UNAVAILABLE" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build()
            )
        }.generateKey()
    }
    private fun encrypt(value: String, origin: String, device: String, scope: CloudIdentityStore.Scope): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        cipher.updateAAD(aad(origin, device, scope))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun aad(origin: String, device: String, scope: CloudIdentityStore.Scope): ByteArray = CloudWireJson.encode(listOf(alias, origin, device, scope.service, scope.library)).toByteArray(Charsets.UTF_8)

    private fun encode(s: State): Map<String, Any?> = linkedMapOf(
        "version" to 1, "generation" to s.generation, "origin" to s.origin, "device" to s.device,
        "service" to s.scope.service, "library" to s.scope.library, "incarnation" to s.incarnation, "enabled" to s.enabled,
        "credential" to s.encryptedCredential, "highest" to s.highest?.let(CloudApplyPlanJson::head), "message" to s.message,
        "lastSuccess" to s.lastSuccess.toString(), "authorityRejected" to s.authorityRejected
    )
    fun reconnectPending(): Boolean = reconnectFile.baseFile.exists() || File(reconnectFile.baseFile.path + ".bak").exists()
    fun pendingReconnect(): Reconnect? = TagRuntime.lock.withLock {
        if (!reconnectPending()) return@withLock null
        val o = CloudWireJson.obj(CloudTransferStore.read(reconnectFile, 128 * 1024).toString(Charsets.UTF_8))
        check(o.getInt("version") == 1) { "CLOUD_RECONNECT_RECORD_INVALID" }
        val p = Reconnect(decode(o.getObject("previous")), decode(o.getObject("next")))
        check(
            !p.previous.enabled && !p.next.enabled && p.previous.origin == p.next.origin &&
                p.previous.scope.library == p.next.scope.library && p.previous.scope.service != p.next.scope.service &&
                p.previous.device != p.next.device && p.previous.incarnation == p.next.incarnation &&
                p.previous.generation != p.next.generation && Regex("[0-9a-f-]{36}").matches(p.next.generation)
        ) { "CLOUD_RECONNECT_RECORD_INVALID" }
        p
    }

    /** Persist both encrypted connections before any old transfer or native metadata changes. */
    fun beginReconnect(expected: String, token: String, device: String, scope: CloudIdentityStore.Scope, head: CloudHead): Reconnect = TagRuntime.lock.withLock {
        val old = requireCurrent(expected)
        check(old.scope.library == scope.library && old.scope.service != scope.service && old.device != device) { "CLOUD_RECOVERED_SCOPE_REQUIRED" }
        check(token.startsWith("$device.") && Regex("[A-Za-z0-9_-]{1,128}\\.[0-9a-f]{64}").matches(token)) { "INVALID_DEVICE_TOKEN" }
        val next = State(
            UUID.randomUUID().toString(), old.origin, device, scope, old.incarnation, false,
            encrypt(token, old.origin, device, scope), head, "Reconnected. Sync is off. Review the cloud copy before enabling sync.", 0
        )
        val previous = old.copy(generation = UUID.randomUUID().toString(), enabled = false, message = "Finish reconnecting after cloud recovery. Local edits are safe.")
        save(previous)
        val bytes = CloudWireJson.encode(mapOf("version" to 1, "previous" to encode(previous), "next" to encode(next))).toByteArray(Charsets.UTF_8)
        check(bytes.size <= 128 * 1024) { "CONNECTION_TOO_LARGE" }
        CloudTransferStore.write(reconnectFile, bytes)
        Reconnect(previous, next)
    }
    fun completeReconnect(p: Reconnect, afterSave: () -> Unit) = TagRuntime.lock.withLock {
        check(pendingReconnect()?.next?.generation == p.next.generation && read()?.generation in setOf(p.previous.generation, p.next.generation)) { "CLOUD_CONNECTION_CHANGED" }
        save(p.next)
        afterSave()
        reconnectFile.delete()
    }
    fun detachPending(): Boolean = detachFile.baseFile.exists() || File(detachFile.baseFile.path + ".bak").exists()
    fun pendingDetach(): Detach? = TagRuntime.lock.withLock {
        if (!detachPending()) return@withLock null
        val o = CloudWireJson.obj(CloudTransferStore.read(detachFile, 128 * 1024).toString(Charsets.UTF_8))
        check(o.getInt("version") == 1 && Regex("[0-9a-f-]{36}").matches(o.getString("archive"))) { "CLOUD_DETACH_RECORD_INVALID" }
        val previous = decode(o.getObject("previous"))
        check(previous.authorityRejected && !previous.enabled) { "CLOUD_DETACH_RECORD_INVALID" }
        Detach(previous, o.getString("archive"))
    }
    fun beginDetach(expected: String): Detach = TagRuntime.lock.withLock {
        val old = requireCurrent(expected)
        check(old.authorityRejected && !old.enabled) { "CLOUD_DETACH_REQUIRES_REJECTED_AUTHORITY" }
        val previous = old.copy(
            generation = UUID.randomUUID().toString(),
            enabled = false,
            message = "Finish the local disconnect. Pending records are preserved; remote outcomes are not cleared."
        )
        save(previous)
        val p = Detach(previous, UUID.randomUUID().toString())
        CloudTransferStore.write(detachFile, CloudWireJson.encode(mapOf("version" to 1, "archive" to p.archive, "previous" to encode(previous))).toByteArray())
        p
    }
    fun archiveDetachedState(p: Detach, archive: File) = TagRuntime.lock.withLock {
        check(pendingDetach()?.archive == p.archive) { "CLOUD_CONNECTION_CHANGED" }
        CloudTransferStore.write(AtomicFile(File(archive, "connection.json")), CloudWireJson.encode(encode(p.previous)).toByteArray())
    }
    private fun retiredDevices(): Set<String> {
        if (!retiredDevicesFile.baseFile.exists() && !File(retiredDevicesFile.baseFile.path + ".bak").exists()) return emptySet()
        val o = CloudWireJson.obj(CloudTransferStore.read(retiredDevicesFile, 65536).toString(Charsets.UTF_8))
        check(o.getInt("version") == 1) { "CLOUD_RETIRED_DEVICE_RECORD_INVALID" }
        val values = o.getArray("devices").map { it as String }.toSet()
        check(values.size <= 256 && values.all { Regex("[0-9a-f]{64}").matches(it) }) { "CLOUD_RETIRED_DEVICE_RECORD_INVALID" }
        return values
    }
    private fun deviceIdentity(origin: String, scope: CloudIdentityStore.Scope, device: String) = CloudWireJson.sha256(CloudWireJson.encode(listOf(origin, scope.service, scope.library, device)))
    fun requireFreshDevice(origin: String, scope: CloudIdentityStore.Scope, device: String) = TagRuntime.lock.withLock {
        check(deviceIdentity(origin, scope, device) !in retiredDevices()) { "CLOUD_FRESH_DEVICE_REQUIRED" }
    }
    fun completeDetach(p: Detach) = TagRuntime.lock.withLock {
        check(pendingDetach()?.archive == p.archive && (read() == null || read()!!.generation == p.previous.generation)) { "CLOUD_CONNECTION_CHANGED" }
        val identities = retiredDevices() + deviceIdentity(p.previous.origin, p.previous.scope, p.previous.device)
        check(identities.size <= 256) { "CLOUD_RETIRED_DEVICE_LIMIT" }
        CloudTransferStore.write(retiredDevicesFile, CloudWireJson.encode(mapOf("version" to 1, "devices" to identities.sorted())).toByteArray())
        file.delete()
        check(read() == null) { "CLOUD_DETACH_STORAGE_UNAVAILABLE" }
        detachFile.delete()
        check(!detachPending()) { "CLOUD_DETACH_STORAGE_UNAVAILABLE" }
    }
    private fun save(s: State) {
        check(TagRuntime.lock.isHeldByCurrentThread) { "CONNECTION_WRITE_REQUIRES_BARRIER" }
        check(directory.isDirectory || directory.mkdirs()) { "CLOUD_STORAGE_UNAVAILABLE" }
        val bytes = CloudWireJson.encode(encode(s)).toByteArray(Charsets.UTF_8)
        check(bytes.size <= 65536) { "CONNECTION_TOO_LARGE" }
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (e: Throwable) {
            file.failWrite(output)
            throw e
        }
    }
}
