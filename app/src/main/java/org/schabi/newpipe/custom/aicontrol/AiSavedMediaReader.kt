/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import java.util.concurrent.Callable
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.custom.library.LibraryRepository
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

internal fun interface AiSavedMediaLookup {
    fun read(items: List<AiStreamRef>, checkActive: () -> Unit): List<StreamInfoItem>
}

/** IO-thread snapshot: exact service/URL plus canonical membership, never title-based identity. */
internal class AiSavedMediaReader(context: Context) : AiSavedMediaLookup {
    private val app = context.applicationContext
    override fun read(items: List<AiStreamRef>, checkActive: () -> Unit): List<StreamInfoItem> {
        checkActive()
        val db = NewPipeDatabase.getInstance(app)
        return db.runInTransaction(
            Callable {
                checkActive()
                val library = LibraryRepository.boundLibraryId(db) ?: throw AiFailure("LIBRARY_NOT_READY", AiOutcome.NOT_EXECUTED)
                items.map { ref ->
                    checkActive()
                    AiMediaHostPolicy.validate(ref.url)
                    db.openHelper.writableDatabase.query(
                        SimpleSQLiteQuery(
                            "SELECT s.service_id,s.url,s.title,s.stream_type,s.duration,s.uploader,s.thumbnail_url FROM streams s " +
                                "WHERE s.uid = ? AND EXISTS (SELECT 1 FROM playlist_stream_join j WHERE j.stream_id = s.uid AND j.playlist_id = ?)",
                            arrayOf(ref.streamId, library)
                        )
                    ).use { cursor ->
                        if (!cursor.moveToFirst()) throw AiFailure("NOT_FOUND", AiOutcome.NOT_EXECUTED)
                        val sid = cursor.getInt(0)
                        val service = try {
                            NewPipe.getService(sid).serviceInfo.name
                        } catch (_: Exception) {
                            throw AiFailure("NOT_FOUND", AiOutcome.NOT_EXECUTED)
                        }
                        if (service != ref.service || cursor.getString(1) != ref.url) throw AiFailure("NOT_FOUND", AiOutcome.NOT_EXECUTED)
                        StreamEntity(
                            serviceId = sid,
                            url = ref.url,
                            title = cursor.getString(2).orEmpty(),
                            streamType = StreamType.valueOf(cursor.getString(3)),
                            duration = cursor.getLong(4),
                            uploader = cursor.getString(5).orEmpty(),
                            thumbnailUrl = cursor.getString(6)
                        ).toStreamInfoItem()
                    }
                }.also { checkActive() }
            }
        )
    }
}
