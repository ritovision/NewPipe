/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.local.holder

import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.R
import org.schabi.newpipe.custom.aicontrol.AiControlActivity
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity
import org.schabi.newpipe.local.LocalItemBuilder

/** Render remote playlist bookmarks without touching the user's local playlists or database. */
class RemotePlaylistItemHolderAndroidTest {
    @Test fun nullRemoteBookmarkCountRendersWithoutCrashingOrReusingStaleCount() {
        check(BuildConfig.APPLICATION_ID == "org.schabi.newpipe.debug.featureaicontrol")
        val scenario = ActivityScenario.launch(AiControlActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val builder = LocalItemBuilder(activity)
                val parent = FrameLayout(activity)
                val holders = listOf(
                    RemotePlaylistItemHolder(builder, parent),
                    RemoteBookmarkPlaylistItemHolder(builder, parent)
                )
                fun row(count: Long?) = PlaylistRemoteEntity(
                    orderingName = "Synthetic remote bookmark",
                    serviceId = 0,
                    url = "https://www.youtube.com/playlist?list=PLsynthetic",
                    thumbnailUrl = null,
                    uploader = null,
                    streamCount = count
                )
                for (holder in holders) {
                    val countLabel = holder.itemView.findViewById<TextView>(R.id.itemStreamCountView)
                    holder.updateFromItem(row(12L), null, null)
                    assertEquals("12", countLabel.text.toString())
                    holder.updateFromItem(row(null), null, null)
                    assertEquals("", countLabel.text.toString())
                    holder.updateFromItem(row(7L), null, null)
                    assertEquals("7", countLabel.text.toString())
                    holder.updateFromItem(row(null), null, null)
                    assertEquals("", countLabel.text.toString())
                }
            }
        } finally {
            scenario.close()
        }
    }
}
