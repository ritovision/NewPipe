/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** Private keys are non-exportable Android Keystore entries, not APK constants or backup data. */
internal class AiDeviceKeys {
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun alias(device: String): String {
        AiProtocol.requireUuid(device)
        return PREFIX + device
    }

    fun exists(device: String): Boolean = keys().containsAlias(alias(device))

    fun create(device: String): String {
        val alias = alias(device)
        if (!keys().containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(false)
                        .build()
                )
            }.generateKeyPair()
        }
        return publicKey(device)
    }

    fun publicKey(device: String): String = encode(
        keys().getCertificate(alias(device))?.publicKey?.encoded
            ?: throw AiFailure("DEVICE_KEY_UNAVAILABLE", AiOutcome.NOT_EXECUTED)
    )

    fun sign(device: String, message: String): String {
        val bytes = message.toByteArray(Charsets.UTF_8)
        AiProtocol.checkInput(bytes.size <= 4096)
        val privateKey = keys().getKey(alias(device), null) as? java.security.PrivateKey
            ?: throw AiFailure("DEVICE_KEY_UNAVAILABLE", AiOutcome.NOT_EXECUTED)
        return encode(
            Signature.getInstance("SHA256withECDSA").run {
                initSign(privateKey)
                update(bytes)
                sign()
            }
        )
    }

    fun deleteOwnedKeys() {
        val store = keys()
        store.aliases().toList().filter { it.startsWith(PREFIX) }.forEach(store::deleteEntry)
    }

    companion object {
        private const val PREFIX = "rv.newpipe.ai."
        fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
