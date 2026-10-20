/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudRecoveryFormatTest {
    private fun fixture() = CloudWireJson.obj(checkNotNull(javaClass.getResourceAsStream("/cloud/recovery-v1.json")).bufferedReader().use { it.readText() })

    @Test fun bothManifestDigestsMatchIndependentTypeScriptVectors() {
        val f = fixture()
        assertEquals(f.getString("digest"), CloudRecoveryFormat.read(f["manifest"]).digest())
        assertEquals(f.getString("nativeDigest"), CloudRecoveryFormat.read(f["nativeManifest"]).digest())
    }

    @Test fun exactCloudArchiveValidatesPagesAndFingerprint() {
        val f = fixture()
        val m = CloudRecoveryFormat.read(f["manifest"])
        val file = Files.createTempFile("cloud-recovery-format-", ".archive").toFile()
        try {
            file.writeText(f.getString("archive"), Charsets.UTF_8)
            assertEquals(m, CloudRecoveryFormat.fromFile(file, m.source))
            assertEquals(m.source!!.head.fingerprint, CloudCatalogCodec.fingerprint(CloudRecoveryFormat.readCatalog(file, m).catalog))
            assertArrayEquals(file.readBytes(), CloudRecoveryFormat.part(file, m, 1))
        } finally {
            file.delete()
        }
    }

    @Test fun truncatedArchiveAndChangedPartAreRejected() {
        val f = fixture()
        val m = CloudRecoveryFormat.read(f["manifest"])
        val file = Files.createTempFile("cloud-recovery-corrupt-", ".archive").toFile()
        try {
            file.writeText(f.getString("archive").dropLast(1), Charsets.UTF_8)
            assertThrows(IllegalStateException::class.java) { CloudRecoveryFormat.readCatalog(file, m) }
            val bytes = f.getString("archive").toByteArray()
            bytes[0] = 0
            file.writeBytes(bytes)
            assertThrows(IllegalStateException::class.java) { CloudRecoveryFormat.part(file, m, 1) }
        } finally {
            file.delete()
        }
    }

    @Test fun extraFieldsAndUnsupportedNativeValidationAreRejected() {
        val f = fixture()
        val m = f.getObject("nativeManifest")
        m["producerValidation"] = "unverified"
        assertThrows(IllegalStateException::class.java) { CloudRecoveryFormat.read(m) }
        m["producerValidation"] = "native-restore-v1"
        m["extra"] = 1
        assertThrows(IllegalArgumentException::class.java) { CloudRecoveryFormat.read(m) }
    }

    @Test fun hardCapAndMultipartShapeAreCheckedWithoutAllocatingMaximumPayload() {
        val f = fixture().getObject("manifest")
        f["bytes"] = CloudRecoveryFormat.MAX_BYTES + 1
        assertThrows(IllegalArgumentException::class.java) { CloudRecoveryFormat.read(f) }
        val native = fixture().getObject("nativeManifest")
        native["parts"] = emptyList<Any>()
        assertThrows(IllegalStateException::class.java) { CloudRecoveryFormat.read(native) }
    }
}
