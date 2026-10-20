/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
import org.schabi.newpipe.custom.tagging.TagRuntime

/** One bounded, durable local apply plan. Chunks keep payload rows below Android CursorWindow limits. */
internal object CloudApplyStore {
    const val MAX_PAYLOAD_BYTES = 64 * 1024 * 1024
    private const val CHUNK_BYTES = 32 * 1024
    fun payloadLimit(maxHeap: Long = Runtime.getRuntime().maxMemory()): Int = minOf(MAX_PAYLOAD_BYTES.toLong(), maxOf(1024L * 1024, maxHeap / 12)).toInt()
    data class Pending(
        val operation: String,
        val incarnation: String,
        val beforeFingerprint: String,
        val targetFingerprint: String,
        val digest: String,
        val bytes: Int,
        val chunks: Int
    )

    fun createTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE cloud_apply_pending (slot INTEGER PRIMARY KEY CHECK(slot=1)," +
                "operation_id TEXT NOT NULL,incarnation TEXT NOT NULL,before_fingerprint TEXT NOT NULL," +
                "target_fingerprint TEXT NOT NULL,plan_digest TEXT NOT NULL,payload_bytes INTEGER NOT NULL," +
                "chunk_count INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE cloud_apply_payload (slot INTEGER NOT NULL CHECK(slot=1) REFERENCES cloud_apply_pending(slot) ON DELETE CASCADE," +
                "chunk_index INTEGER NOT NULL,bytes BLOB NOT NULL,PRIMARY KEY(slot,chunk_index))"
        )
        db.execSQL(
            "CREATE TABLE cloud_sync_baseline (slot INTEGER PRIMARY KEY CHECK(slot=1),incarnation TEXT NOT NULL," +
                "operation_id TEXT NOT NULL,head_json TEXT NOT NULL,accepted_at INTEGER NOT NULL)"
        )
    }
    data class Baseline(val incarnation: String, val operation: String, val head: CloudHead)
    fun baseline(db: SQLiteDatabase): Baseline? = db.rawQuery(
        "SELECT incarnation,operation_id,head_json FROM cloud_sync_baseline WHERE slot=1",
        null
    ).use { c ->
        if (!c.moveToFirst()) {
            null
        } else {
            Baseline(
                c.getString(0),
                c.getString(1),
                CloudApplyPlanJson.readHead(com.grack.nanojson.JsonParser.`object`().from(c.getString(2)))
            )
        }
    }
    fun accept(db: SQLiteDatabase, incarnation: String, operation: String, head: CloudHead) {
        check(TagRuntime.lock.isHeldByCurrentThread && db.inTransaction()) { "CLOUD_BASELINE_REQUIRES_TRANSACTION" }
        db.execSQL(
            "INSERT OR REPLACE INTO cloud_sync_baseline VALUES(1,?,?,?,?)",
            arrayOf<Any>(incarnation, operation, CloudApplyPlanJson.headJson(head), System.currentTimeMillis())
        )
    }
    fun pending(db: SQLiteDatabase): Pending? = db.rawQuery(
        "SELECT operation_id,incarnation,before_fingerprint,target_fingerprint,plan_digest,payload_bytes,chunk_count FROM cloud_apply_pending WHERE slot=1",
        null
    ).use { c ->
        if (!c.moveToFirst()) return@use null
        val value = Pending(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5), c.getInt(6))
        check(!c.moveToNext() && value.bytes in 1..MAX_PAYLOAD_BYTES && value.chunks == (value.bytes + CHUNK_BYTES - 1) / CHUNK_BYTES) { "CORRUPT_CLOUD_PENDING" }
        check(listOf(value.beforeFingerprint, value.targetFingerprint, value.digest).all { Regex("[0-9a-f]{64}").matches(it) }) { "CORRUPT_CLOUD_PENDING" }
        value
    }
    fun payload(db: SQLiteDatabase, p: Pending): ByteArray {
        check(p.bytes <= payloadLimit()) { "CLOUD_RECOVERY_MEMORY_BUDGET" }
        val output = ByteArray(p.bytes)
        db.rawQuery("SELECT chunk_index,bytes FROM cloud_apply_payload WHERE slot=1 ORDER BY chunk_index LIMIT ?", arrayOf((p.chunks + 1).toString())).use { c ->
            var index = 0
            while (c.moveToNext()) {
                check(index < p.chunks && c.getInt(0) == index) { "CORRUPT_CLOUD_PAYLOAD" }
                val bytes = c.getBlob(1)
                val expected = if (index + 1 == p.chunks) p.bytes - index * CHUNK_BYTES else CHUNK_BYTES
                check(bytes.size == expected) { "CORRUPT_CLOUD_PAYLOAD" }
                bytes.copyInto(output, index * CHUNK_BYTES)
                index++
            }
            check(index == p.chunks) { "CORRUPT_CLOUD_PAYLOAD" }
        }
        return output.also { check(hash(it) == p.digest) { "CORRUPT_CLOUD_PAYLOAD_DIGEST" } }
    }
    fun save(db: SQLiteDatabase, operation: String, incarnation: String, before: String, target: String, payload: ByteArray): Pending {
        check(TagRuntime.lock.isHeldByCurrentThread && db.inTransaction()) { "CLOUD_JOURNAL_REQUIRES_TRANSACTION" }
        check(pending(db) == null) { "CLOUD_APPLY_ALREADY_PENDING" }
        check(payload.size in 1..payloadLimit()) { "CLOUD_PLAN_SIZE_LIMIT" }
        val pending = Pending(operation, incarnation, before, target, hash(payload), payload.size, (payload.size + CHUNK_BYTES - 1) / CHUNK_BYTES)
        db.execSQL(
            "INSERT INTO cloud_apply_pending VALUES(1,?,?,?,?,?,?,?)",
            arrayOf<Any>(pending.operation, pending.incarnation, pending.beforeFingerprint, pending.targetFingerprint, pending.digest, pending.bytes, pending.chunks)
        )
        db.compileStatement("INSERT INTO cloud_apply_payload VALUES(1,?,?)").use { statement ->
            for (index in 0 until pending.chunks) {
                val start = index * CHUNK_BYTES
                statement.bindLong(1, index.toLong())
                statement.bindBlob(2, payload.copyOfRange(start, minOf(start + CHUNK_BYTES, payload.size)))
                statement.executeInsert()
                statement.clearBindings()
            }
        }
        return pending
    }
    fun clearPending(db: SQLiteDatabase) {
        check(db.inTransaction()) { "CLOUD_JOURNAL_REQUIRES_TRANSACTION" }
        db.delete("cloud_apply_payload", null, null)
        db.delete("cloud_apply_pending", null, null)
    }
    fun discard(db: SQLiteDatabase) {
        clearPending(db)
        db.delete("cloud_sync_baseline", null, null)
    }
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        (it.toInt() and 255).toString(16).padStart(2, '0')
    }
}
