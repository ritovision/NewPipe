/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import java.nio.file.Files
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CatalogFileSwapTest {
    private lateinit var dir: Path
    private lateinit var stage: Path
    private val names = listOf("newpipe.db", "newpipe.db-wal", "ritovision-tags.db", "ritovision-library.binding")
    private class Death : Error()

    @Before fun setup() {
        dir = Files.createTempDirectory("catalog-swap-test-")
        stage = Files.createTempDirectory("catalog-swap-source-")
        names.forEach { Files.write(dir.resolve(it), "old-$it".toByteArray()) }
        names.filterNot { it.endsWith("-wal") }.forEach { Files.write(stage.resolve(it), "new-$it".toByteArray()) }
    }

    @After fun cleanup() {
        CatalogFileSwap.fault = null
        CatalogFileSwap.deleteTree(dir)
        CatalogFileSwap.deleteTree(stage)
    }
    private fun replacements() = names.associateWith { stage.resolve(it).takeIf { p -> Files.exists(p) } }
    private fun oldSet() {
        names.forEach { assertArrayEquals("old-$it".toByteArray(), Files.readAllBytes(dir.resolve(it))) }
    }
    private fun newSet() {
        names.filterNot { it.endsWith("-wal") }.forEach { assertArrayEquals("new-$it".toByteArray(), Files.readAllBytes(dir.resolve(it))) }
        assertFalse(Files.exists(dir.resolve("newpipe.db-wal")))
    }

    @Test fun completeSwapPublishesWholeNewSet() {
        CatalogFileSwap.replace(dir, replacements())
        newSet()
        assertFalse(Files.exists(dir.resolve(CatalogFileSwap.JOURNAL)))
    }

    @Test fun interruptedBeforeFirstFileRestoresWholeOldSet() {
        CatalogFileSwap.fault = { if (it == "prepared") throw Death() }
        assertThrows(Death::class.java) { CatalogFileSwap.replace(dir, replacements()) }
        CatalogFileSwap.fault = null
        CatalogFileSwap.recover(dir)
        oldSet()
    }

    @Test fun interruptedBetweenEveryFileAlwaysRecoversWholeOldSet() {
        for (name in names) {
            CatalogFileSwap.fault = { if (it == "new:$name") throw Death() }
            assertThrows(Death::class.java) { CatalogFileSwap.replace(dir, replacements()) }
            CatalogFileSwap.fault = null
            CatalogFileSwap.recover(dir)
            oldSet()
        }
    }

    @Test fun interruptedAfterCommitMarkerRetainsWholeNewSet() {
        CatalogFileSwap.fault = { if (it == "committed") throw Death() }
        assertThrows(Death::class.java) { CatalogFileSwap.replace(dir, replacements()) }
        CatalogFileSwap.fault = null
        CatalogFileSwap.recover(dir)
        newSet()
    }

    @Test fun ordinaryFailureRollsBackWithoutDeletingMissingFileHistory() {
        Files.delete(dir.resolve("ritovision-tags.db"))
        CatalogFileSwap.fault = { if (it == "new:ritovision-tags.db") throw java.io.IOException("synthetic I/O failure") }
        assertThrows(java.io.IOException::class.java) { CatalogFileSwap.replace(dir, replacements()) }
        assertFalse(Files.exists(dir.resolve("ritovision-tags.db")))
        assertArrayEquals("old-newpipe.db".toByteArray(), Files.readAllBytes(dir.resolve("newpipe.db")))
    }

    @Test fun missingRecoveryMaterialFailsClosedAndKeepsJournal() {
        CatalogFileSwap.fault = { if (it == "new:newpipe.db") throw Death() }
        assertThrows(Death::class.java) { CatalogFileSwap.replace(dir, replacements()) }
        Files.delete(dir.resolve(CatalogFileSwap.JOURNAL).resolve("old.2"))
        CatalogFileSwap.fault = null
        assertThrows(Exception::class.java) { CatalogFileSwap.recover(dir) }
        assertTrue(Files.exists(dir.resolve(CatalogFileSwap.JOURNAL)))
        assertArrayEquals("new-newpipe.db".toByteArray(), Files.readAllBytes(dir.resolve("newpipe.db")))
    }

    @Test fun startupCleansInertStagingAndRetiredCopiesButNotUnknownFolders() {
        val orphan = Files.createDirectories(dir.resolve(".rv-swap-staging-dead"))
        val retired = Files.createDirectories(dir.resolve(".rv-swap-retired-dead"))
        val unrelated = Files.createDirectories(dir.resolve("do-not-touch"))
        Files.write(orphan.resolve("old.db"), byteArrayOf(1))
        Files.write(retired.resolve("new.db"), byteArrayOf(2))
        CatalogFileSwap.recover(dir)
        assertFalse(Files.exists(orphan))
        assertFalse(Files.exists(retired))
        assertTrue(Files.exists(unrelated))
        oldSet()
    }
}
