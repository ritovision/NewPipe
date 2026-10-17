/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import android.content.Context
import org.schabi.newpipe.custom.tagging.StreamKey
import org.schabi.newpipe.custom.tagging.TagRepository
import org.schabi.newpipe.custom.tagging.TagRuntime
import org.schabi.newpipe.extractor.NewPipe

/** Local button path reuses verified native playback, not remote authorization or an AI connection. */
internal object AiLocalTagPlayback {
    fun play(context: Context, repository: TagRepository, keys: List<StreamKey>, append: Boolean, cancelled: () -> Boolean) {
        require(keys.size in 1..10000) { "Choose between 1 and 10,000 videos for playback" }
        val check = {
            check(!cancelled()) { "Playback request cancelled" }
            TagRuntime.assertAvailable()
        }
        check()
        val streams = repository.resolveSnapshot(keys)
        val refs = streams.map { AiStreamRef(it.uid, NewPipe.getService(it.serviceId).serviceInfo.name, it.url) }
        AiAndroidPlaybackPort(context).execute(if (append) AiCatalogCommand.AppendQueue(refs) else AiCatalogCommand.PlayItems(refs), check)
    }
}
