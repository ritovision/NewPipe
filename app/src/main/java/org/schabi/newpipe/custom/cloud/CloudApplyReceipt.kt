/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration

/** One bounded commit witness. No credential, user media content or replayable SQL is stored here. */
@Entity(tableName = "rv_cloud_apply_receipt")
data class CloudApplyReceipt(
    @PrimaryKey val slot: Int = 1,
    @ColumnInfo(name = "operation_id") val operationId: String,
    val incarnation: String,
    @ColumnInfo(name = "target_fingerprint") val targetFingerprint: String,
    @ColumnInfo(name = "plan_digest") val planDigest: String
)

@Dao
interface CloudApplyReceiptDao {
    @Query("SELECT * FROM rv_cloud_apply_receipt WHERE slot=1")
    fun current(): CloudApplyReceipt?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun record(receipt: CloudApplyReceipt)
}

/** NP9/RV1 -> NP9/RV2. No upstream-owned table or previous data is rewritten. */
object CloudDatabaseMigration {
    val FROM_9001 = Migration(9001, 9002) { db ->
        db.execSQL(
            "CREATE TABLE `rv_cloud_apply_receipt` (`slot` INTEGER NOT NULL, `operation_id` TEXT NOT NULL," +
                " `incarnation` TEXT NOT NULL, `target_fingerprint` TEXT NOT NULL, `plan_digest` TEXT NOT NULL," +
                " PRIMARY KEY(`slot`))"
        )
    }
}
