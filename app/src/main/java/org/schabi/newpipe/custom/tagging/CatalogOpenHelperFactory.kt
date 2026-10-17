/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteTransactionListener
import android.os.CancellationSignal
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Before-open journal recovery plus an admission barrier covering ALL Room writers. */
internal class CatalogOpenHelperFactory(private val directory: Path) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper = Guard(FrameworkSQLiteOpenHelperFactory().create(configuration), directory, TagRuntime.generation)

    private class Guard(
        private val delegate: SupportSQLiteOpenHelper,
        private val directory: Path,
        private val generation: Long
    ) : SupportSQLiteOpenHelper by delegate {
        private var recovered = false

        @Volatile var opened: FencedDatabase? = null
            private set

        private fun open(write: Boolean): SupportSQLiteDatabase = synchronized(this) {
            // Returning an existing proxy permits endTransaction/inTransaction on the owner
            // thread. Every actual operation below still checks admission; no reopen occurs.
            opened?.let { return@synchronized it }
            TagRuntime.assertAvailable()
            check(generation == TagRuntime.generation) { "This database was replaced. Restart the app." }
            if (!recovered) {
                CatalogFileSwap.recover(directory)
                recovered = true
            }
            FencedDatabase(if (write) delegate.writableDatabase else delegate.readableDatabase, generation).also { opened = it }
        }
        override val writableDatabase: SupportSQLiteDatabase get() = open(true)
        override val readableDatabase: SupportSQLiteDatabase get() = open(false)
        override fun setWriteAheadLoggingEnabled(enabled: Boolean) = synchronized(this) {
            TagRuntime.assertAvailable()
            check(generation == TagRuntime.generation)
            delegate.setWriteAheadLoggingEnabled(enabled)
        }
        override fun close() {
            // Do not hold the helper monitor while waiting for a transaction that needs its
            // getter to end. The close call is IO-only in the coordinated import path.
            opened?.freeze()
            synchronized(this) { delegate.close() }
        }
    }

    private class FencedDatabase(private val db: SupportSQLiteDatabase, private val generation: Long) : SupportSQLiteDatabase by db {
        private val operations = ReentrantReadWriteLock(true)
        private val transactionDepth = ThreadLocal.withInitial { 0 }

        @Volatile private var frozen = false

        private fun acquire() {
            operations.readLock().lock()
            val draining = (transactionDepth.get() ?: 0) > 0
            if (!draining && (frozen || TagRuntime.restoring || generation != TagRuntime.generation)) {
                operations.readLock().unlock()
                // Expected lifecycle cancellation, not an uncaught programming error:
                // Room may schedule an invalidation transaction while restore is draining.
                // It must stop normally rather than reach the app's crash handler.
                throw CancellationException("This Library is being replaced. Restart before making changes.")
            }
        }
        private inline fun <T> operation(block: () -> T): T {
            acquire()
            try {
                return block()
            } finally {
                operations.readLock().unlock()
            }
        }
        private fun begin(block: () -> Unit) {
            acquire()
            try {
                block()
                transactionDepth.set((transactionDepth.get() ?: 0) + 1)
            } catch (error: Throwable) {
                operations.readLock().unlock()
                throw error
            }
            // Keep this read lease for the entire transaction, including gaps between SQL.
        }
        fun freeze() {
            check((transactionDepth.get() ?: 0) == 0) { "Cannot replace Library within a transaction" }
            check(operations.writeLock().tryLock(3, TimeUnit.SECONDS)) { "Database work is still active. Import stopped before replacement; restart and retry." }
            try {
                frozen = true
            } finally {
                operations.writeLock().unlock()
            }
        }
        override fun beginTransaction() = begin { db.beginTransaction() }
        override fun beginTransactionNonExclusive() = begin { db.beginTransactionNonExclusive() }
        override fun beginTransactionReadOnly() = begin { db.beginTransactionReadOnly() }
        override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) = begin { db.beginTransactionWithListener(transactionListener) }
        override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) = begin { db.beginTransactionWithListenerNonExclusive(transactionListener) }
        override fun beginTransactionWithListenerReadOnly(transactionListener: SQLiteTransactionListener) = begin { db.beginTransactionWithListenerReadOnly(transactionListener) }
        override fun endTransaction() {
            try {
                db.endTransaction()
            } finally {
                if ((transactionDepth.get() ?: 0) > 0) {
                    transactionDepth.set((transactionDepth.get() ?: 0) - 1)
                    operations.readLock().unlock()
                }
            }
        }
        override fun setTransactionSuccessful() = operation { db.setTransactionSuccessful() }
        override fun query(query: String): Cursor = operation { db.query(query) }
        override fun query(query: String, bindArgs: Array<out Any?>): Cursor = operation { db.query(query, bindArgs) }
        override fun query(query: SupportSQLiteQuery): Cursor = operation { db.query(query) }
        override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor = operation { db.query(query, cancellationSignal) }
        override fun execSQL(sql: String) = operation { db.execSQL(sql) }
        override fun execSQL(sql: String, bindArgs: Array<out Any?>) = operation { db.execSQL(sql, bindArgs) }
        override fun execPerConnectionSQL(sql: String, bindArgs: Array<out Any?>?) = operation { db.execPerConnectionSQL(sql, bindArgs) }
        override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues): Long = operation { db.insert(table, conflictAlgorithm, values) }
        override fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int = operation { db.delete(table, whereClause, whereArgs) }
        override fun update(table: String, conflictAlgorithm: Int, values: ContentValues, whereClause: String?, whereArgs: Array<out Any?>?): Int = operation { db.update(table, conflictAlgorithm, values, whereClause, whereArgs) }
        override fun compileStatement(sql: String): SupportSQLiteStatement = operation {
            val statement = db.compileStatement(sql)
            object : SupportSQLiteStatement by statement {
                override fun execute() = operation { statement.execute() }
                override fun executeInsert(): Long = operation { statement.executeInsert() }
                override fun executeUpdateDelete(): Int = operation { statement.executeUpdateDelete() }
                override fun simpleQueryForLong(): Long = operation { statement.simpleQueryForLong() }
                override fun simpleQueryForString(): String? = operation { statement.simpleQueryForString() }
            }
        }
    }

    companion object {
        /** Called after the new-admission fence, before close/checkpoint/filesystem replacement. */
        fun drain(helper: SupportSQLiteOpenHelper) {
            (helper as? Guard)?.opened?.freeze()
        }
    }
}
