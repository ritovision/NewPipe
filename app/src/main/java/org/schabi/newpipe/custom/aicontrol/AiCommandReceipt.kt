/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query
import androidx.room.migration.Migration

/** No tokens, pairing keys, query text or stream metadata are stored in this table. */
@Entity(
    tableName = "rv_ai_command_receipts",
    primaryKeys = ["device_id", "catalog_epoch", "client_id", "request_id"],
    indices = [Index(value = ["retained_until_ms"])]
)
data class AiCommandReceipt(
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "catalog_epoch") val catalogEpoch: String,
    @ColumnInfo(name = "client_id") val clientId: String,
    @ColumnInfo(name = "request_id") val requestId: String,
    val fingerprint: String,
    @ColumnInfo(name = "required_scopes") val requiredScopes: String,
    @ColumnInfo(name = "result_json") val resultJson: String,
    @ColumnInfo(name = "retained_until_ms") val retainedUntilMs: Long
)

@Dao
interface AiCommandReceiptDao {
    @Query(
        "SELECT * FROM rv_ai_command_receipts WHERE device_id = :deviceId AND catalog_epoch = :epoch " +
            "AND client_id = :clientId AND request_id = :requestId"
    )
    fun find(deviceId: String, epoch: String, clientId: String, requestId: String): AiCommandReceipt?

    @Insert
    fun insert(receipt: AiCommandReceipt)

    @Query("DELETE FROM rv_ai_command_receipts WHERE retained_until_ms <= :nowMs")
    fun deleteExpired(nowMs: Long): Int

    @Query("SELECT COUNT(*) FROM rv_ai_command_receipts")
    fun count(): Int
}

/**
 * This fork's v10 adds only the receipt table. Catalog edits and their receipt use ONE Room
 * transaction, not a separately committed sidecar. On a future upstream v10 merge this fork
 * version must be explicitly reconciled; never silently reuse another migration's number.
 */
object AiDatabaseMigration {
    const val VERSION = 10

    val FROM_UPSTREAM_9 = Migration(9, VERSION) { db ->
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `rv_ai_command_receipts` (
                `device_id` TEXT NOT NULL,
                `catalog_epoch` TEXT NOT NULL,
                `client_id` TEXT NOT NULL,
                `request_id` TEXT NOT NULL,
                `fingerprint` TEXT NOT NULL,
                `required_scopes` TEXT NOT NULL,
                `result_json` TEXT NOT NULL,
                `retained_until_ms` INTEGER NOT NULL,
                PRIMARY KEY(`device_id`, `catalog_epoch`, `client_id`, `request_id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_rv_ai_command_receipts_retained_until_ms` ON `rv_ai_command_receipts` (`retained_until_ms`)")
    }
}
