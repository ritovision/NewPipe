/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.nio.file.Path

/** Recover closed-file import journals on the IO open path, never in Application.onCreate. */
internal class CatalogOpenHelperFactory(private val directory: Path) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val delegate = FrameworkSQLiteOpenHelperFactory().create(configuration)
        return Guard(delegate, directory, TagRuntime.generation)
    }

    private class Guard(
        private val delegate: SupportSQLiteOpenHelper,
        private val directory: Path,
        private val generation: Long
    ) : SupportSQLiteOpenHelper by delegate {
        private var recovered = false

        private fun checkOpen() {
            TagRuntime.assertAvailable()
            check(generation == TagRuntime.generation) { "This database was replaced. Restart before making changes." }
            if (!recovered) {
                CatalogFileSwap.recover(directory)
                recovered = true
            }
        }

        override val writableDatabase: SupportSQLiteDatabase
            get() = synchronized(this) {
                checkOpen()
                delegate.writableDatabase
            }

        override val readableDatabase: SupportSQLiteDatabase
            get() = synchronized(this) {
                checkOpen()
                delegate.readableDatabase
            }

        override fun setWriteAheadLoggingEnabled(enabled: Boolean) = synchronized(this) {
            // Room may configure this during construction; no disk I/O until the actual open.
            TagRuntime.assertAvailable()
            check(generation == TagRuntime.generation) { "This database was replaced" }
            delegate.setWriteAheadLoggingEnabled(enabled)
        }

        override fun close() = synchronized(this) { delegate.close() }
    }
}
