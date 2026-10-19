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
        val lastSuccess: Long
    ) {
        override fun toString(): String = "CloudConnection(enabled=$enabled, credential=[redacted])"
        fun copy(
            generation: String = this.generation,
            incarnation: String = this.incarnation,
            enabled: Boolean = this.enabled,
            highest: CloudHead? = this.highest,
            message: String = this.message,
            lastSuccess: Long = this.lastSuccess
        ) = State(generation, origin, device, scope, incarnation, enabled, encryptedCredential, highest, message, lastSuccess)
    }
    fun read(): State? = TagRuntime.lock.withLock {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withLock null
        val bytes = CloudTransferStore.read(file, 65536)
        val o = CloudWireJson.obj(bytes.toString(Charsets.UTF_8))
        check(o.getInt("version") == 1) { "UNSUPPORTED_CONNECTION_VERSION" }
        State(
            o.getString("generation"), o.getString("origin"), o.getString("device"),
            CloudIdentityStore.Scope(o.getString("service"), o.getString("library")), o.getString("incarnation"),
            o.getBoolean("enabled"), o.getString("credential"), o["highest"]?.let(CloudWireJson::head),
            o.getString("message"), CloudCounter.parse(o.getString("lastSuccess"))
        )
    }
    fun create(origin: String, token: String, device: String, scope: CloudIdentityStore.Scope, incarnation: String, head: CloudHead): State = TagRuntime.lock.withLock {
        check(read() == null) { "FORGET_EXISTING_CONNECTION_FIRST" }
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
        val s = read()
        check(s != null && s.generation == generation && (!enabled || s.enabled)) { "CLOUD_CONNECTION_CHANGED" }
        return s
    }
    fun disable(message: String = "Sync is off. Local edits stay on this device."): State? = TagRuntime.lock.withLock {
        val s = read() ?: return@withLock null
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
    private fun aad(origin: String, device: String, scope: CloudIdentityStore.Scope): ByteArray =
        CloudWireJson.encode(listOf(alias, origin, device, scope.service, scope.library)).toByteArray(Charsets.UTF_8)

    private fun save(s: State) {
        check(TagRuntime.lock.isHeldByCurrentThread) { "CONNECTION_WRITE_REQUIRES_BARRIER" }
        check(directory.isDirectory || directory.mkdirs()) { "CLOUD_STORAGE_UNAVAILABLE" }
        val bytes = CloudWireJson.encode(
            linkedMapOf(
                "version" to 1, "generation" to s.generation, "origin" to s.origin, "device" to s.device,
                "service" to s.scope.service, "library" to s.scope.library, "incarnation" to s.incarnation, "enabled" to s.enabled,
                "credential" to s.encryptedCredential, "highest" to s.highest?.let(CloudApplyPlanJson::head), "message" to s.message,
                "lastSuccess" to s.lastSuccess.toString()
            )
        ).toByteArray(Charsets.UTF_8)
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
