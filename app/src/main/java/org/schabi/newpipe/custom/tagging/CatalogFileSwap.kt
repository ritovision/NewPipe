/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/**
 * Closed-file catalog replacement. No marker => restore ALL old files; COMMITTED => ALL new.
 * Recovery runs before any database opens. A failed replay retains ALL recovery material.
 * Callers own the database quiescence barrier; this object owns durable file ordering only.
 */
internal object CatalogFileSwap {
    const val JOURNAL = ".rv-catalog-swap"
    internal var fault: ((String) -> Unit)? = null

    @Synchronized
    fun replace(directory: Path, replacements: Map<String, Path?>) {
        require(replacements.isNotEmpty() && replacements.size <= 32)
        replacements.keys.forEach(::checkName)
        recover(directory)
        val stage = Files.createTempDirectory(directory, ".rv-swap-staging-")
        val journal = directory.resolve(JOURNAL)
        var published = false
        try {
            val manifest = Properties().apply { setProperty("count", replacements.size.toString()) }
            replacements.entries.forEachIndexed { index, (name, replacement) ->
                manifest.setProperty("name.$index", name)
                val current = directory.resolve(name)
                snapshot(manifest, stage, index, "old", current.takeIf { Files.exists(it) })
                snapshot(manifest, stage, index, "new", replacement)
            }
            Files.newOutputStream(stage.resolve("manifest")).use { manifest.store(it, "closed catalog swap v1") }
            syncFile(stage.resolve("manifest"))
            syncDirectory(stage)
            Files.move(stage, journal, ATOMIC_MOVE)
            published = true
            syncDirectory(directory)
            fault?.invoke("prepared")
            apply(directory, journal, manifest, "new")
            Files.write(journal.resolve("COMMITTED.tmp"), byteArrayOf(1))
            syncFile(journal.resolve("COMMITTED.tmp"))
            Files.move(journal.resolve("COMMITTED.tmp"), journal.resolve("COMMITTED"), ATOMIC_MOVE)
            syncDirectory(journal)
            fault?.invoke("committed")
            retire(directory, journal)
        } catch (error: Exception) {
            if (published) {
                try {
                    recover(directory)
                } catch (recovery: Exception) {
                    error.addSuppressed(recovery)
                }
            }
            throw error
        } finally {
            // Unpublished material cannot describe modified installed files. Once published,
            // only a successful replay/retirement may delete evidence, including after Error.
            if (!published && !Files.exists(journal)) deleteTree(stage)
        }
    }

    @Synchronized
    fun recover(directory: Path) {
        val journal = directory.resolve(JOURNAL)
        if (!Files.exists(journal)) return
        check(Files.isDirectory(journal) && !Files.isSymbolicLink(journal)) { "Invalid catalog recovery directory" }
        val manifest = Properties().also { p -> Files.newInputStream(journal.resolve("manifest")).use { p.load(it) } }
        val count = manifest.getProperty("count")?.toIntOrNull() ?: error("Invalid catalog recovery manifest")
        check(count in 1..32)
        val names = (0 until count).map { manifest.getProperty("name.$it") ?: error("Missing recovery filename") }
        names.forEach(::checkName)
        check(names.distinct().size == count)
        val side = if (Files.exists(journal.resolve("COMMITTED"))) "new" else "old"
        // Validate the ENTIRE chosen set before replacing any target.
        for (index in names.indices) {
            val expectedHash = manifest.getProperty("$side.$index") ?: error("Missing recovery state")
            if (expectedHash != "absent") {
                val source = journal.resolve("$side.$index")
                check(!Files.isSymbolicLink(source) && expectedHash == hash(source)) { "Catalog recovery snapshot is incomplete; recovery material preserved" }
            }
        }
        apply(directory, journal, manifest, side)
        retire(directory, journal)
    }

    private fun snapshot(manifest: Properties, stage: Path, index: Int, side: String, source: Path?) {
        val key = "$side.$index"
        if (source == null) {
            manifest.setProperty(key, "absent")
        } else {
            check(Files.isRegularFile(source) && !Files.isSymbolicLink(source)) { "Invalid catalog source file" }
            val target = stage.resolve(key)
            Files.copy(source, target)
            syncFile(target)
            manifest.setProperty(key, hash(target))
        }
    }

    private fun apply(directory: Path, journal: Path, manifest: Properties, side: String) {
        val count = manifest.getProperty("count").toInt()
        for (index in 0 until count) {
            val name = manifest.getProperty("name.$index")
            checkName(name)
            val destination = directory.resolve(name)
            check(!Files.isSymbolicLink(destination)) { "Invalid catalog destination" }
            if (manifest.getProperty("$side.$index") == "absent") {
                Files.deleteIfExists(destination)
            } else {
                val temporary = journal.resolve("install.$index")
                Files.copy(journal.resolve("$side.$index"), temporary, REPLACE_EXISTING)
                syncFile(temporary)
                Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            }
            syncDirectory(directory)
            fault?.invoke("$side:$name")
        }
    }

    private fun retire(directory: Path, journal: Path) {
        val retired = directory.resolve(".rv-swap-retired-${UUID.randomUUID()}")
        Files.move(journal, retired, ATOMIC_MOVE)
        syncDirectory(directory)
        // Retire atomically BEFORE removing files, so interrupted cleanup is not mistaken
        // for an active journal with missing snapshots on the next startup.
        try {
            deleteTree(retired)
        } catch (_: Exception) { /* Inert private snapshots. */ }
    }

    private fun checkName(name: String) {
        check(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) && name != "manifest") { "Invalid catalog filename" }
    }

    private fun hash(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val bytes = ByteArray(65536)
            while (true) {
                val n = input.read(bytes)
                if (n < 0) break
                digest.update(bytes, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    internal fun syncFile(path: Path) {
        FileChannel.open(path, WRITE).use { it.force(true) }
    }
    internal fun syncDirectory(path: Path) {
        FileChannel.open(path, READ).use { it.force(true) }
    }
    internal fun deleteTree(path: Path) {
        if (Files.exists(path)) Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
