/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import android.os.Looper
import java.lang.ref.WeakReference
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.PlayerService

/** In-process lifecycle reference only. This is not an Android component or an IPC interface. */
object AiPlayerRegistry {
    private var service = WeakReference<PlayerService>(null)

    @JvmStatic
    fun register(value: PlayerService) {
        check(Looper.myLooper() == Looper.getMainLooper())
        service = WeakReference(value)
    }

    @JvmStatic
    fun unregister(value: PlayerService) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (service.get() === value) service.clear()
    }

    internal fun player(): Player? {
        check(Looper.myLooper() == Looper.getMainLooper())
        return service.get()?.player
    }
}
