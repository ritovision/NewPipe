/*
 * SPDX-FileCopyrightText: 2017-2024 NewPipe contributors <https://newpipe.net>
 * SPDX-FileCopyrightText: 2025 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe

import android.content.Context
import androidx.room.Room.databaseBuilder
import androidx.room.RoomDatabase
import kotlin.concurrent.Volatile
import kotlin.concurrent.withLock
import org.schabi.newpipe.custom.aicontrol.AiDatabaseMigration
import org.schabi.newpipe.custom.tagging.CatalogOpenHelperFactory
import org.schabi.newpipe.custom.tagging.TagRuntime
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.Migrations.MIGRATION_1_2
import org.schabi.newpipe.database.Migrations.MIGRATION_2_3
import org.schabi.newpipe.database.Migrations.MIGRATION_3_4
import org.schabi.newpipe.database.Migrations.MIGRATION_4_5
import org.schabi.newpipe.database.Migrations.MIGRATION_5_6
import org.schabi.newpipe.database.Migrations.MIGRATION_6_7
import org.schabi.newpipe.database.Migrations.MIGRATION_7_8
import org.schabi.newpipe.database.Migrations.MIGRATION_8_9

object NewPipeDatabase {

    @Volatile
    private var databaseInstance: AppDatabase? = null

    private fun getDatabase(context: Context): AppDatabase = buildDatabase(context, AppDatabase.DATABASE_NAME, true)

    /** Opens only a coordinator-owned staged copy, without registering it as the live Library. */
    internal fun openStagedCopy(context: Context, path: java.nio.file.Path): AppDatabase = buildDatabase(context, path.toAbsolutePath().toString(), false)

    private fun buildDatabase(context: Context, name: String, guarded: Boolean): AppDatabase {
        val builder = databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            name
        ).addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            AiDatabaseMigration.FROM_UPSTREAM_9
        )
        if (guarded) {
            builder.openHelperFactory(CatalogOpenHelperFactory(context.getDatabasePath(AppDatabase.DATABASE_NAME).toPath().parent))
        } else {
            builder.setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        }
        return builder.build()
    }

    @JvmStatic
    fun getInstance(context: Context): AppDatabase {
        TagRuntime.assertAvailable()
        var result = databaseInstance
        if (result == null) {
            synchronized(NewPipeDatabase::class.java) {
                TagRuntime.assertAvailable()
                result = databaseInstance
                if (result == null) {
                    val created = getDatabase(context)
                    TagRuntime.attach(context, created)
                    databaseInstance = created
                    result = created
                }
            }
        }

        return result!!
    }

    @JvmStatic
    fun checkpoint() {
        val db = checkNotNull(databaseInstance) { "database is not initialized" }
        db.query("pragma wal_checkpoint(full)", null).use { c ->
            if (c.moveToFirst() && c.getInt(0) == 1) throw IllegalStateException("Checkpoint was blocked")
        }
    }

    @JvmStatic
    fun close() = TagRuntime.lock.withLock {
        synchronized(NewPipeDatabase::class.java) {
            databaseInstance?.let { db ->
                TagRuntime.detach(db)
                db.close()
                databaseInstance = null
            }
        }
    }
}
