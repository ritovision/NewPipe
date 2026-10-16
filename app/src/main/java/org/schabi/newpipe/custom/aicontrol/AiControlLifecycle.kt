/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.function.Consumer

/** Narrow Java UI/lifecycle integration. No caller is allowed to wait for Room/player work on main. */
object AiControlLifecycle {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "NewPipe-AI-lifecycle").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    @JvmStatic
    fun keepsProcessAlive(): Boolean = AiControlRuntime.keepsProcessAlive()

    @JvmStatic
    fun beforeImport(context: Context, ready: Runnable, failed: Consumer<Throwable>) {
        val app = context.applicationContext
        worker.execute {
            try {
                AiControlRuntime.get(app).beforeRestore()
                main.post(ready)
            } catch (_: Exception) {
                main.post { failed.accept(IllegalStateException("AI Control could not be safely disconnected; import was not started.")) }
            }
        }
    }

    @JvmStatic
    fun stopForExit(context: Context, ready: Runnable) {
        val app = context.applicationContext
        worker.execute {
            try {
                AiControlRuntime.get(app).beforeRestore(timeoutSeconds = 2)
            } catch (_: Exception) {
                // The panic exit must still happen. Removing only our own keys makes persisted
                // enabled state useless on next launch if the disable write could not complete.
                try {
                    AiDeviceKeys().deleteOwnedKeys()
                } catch (_: Exception) { /* Best-effort key removal. */ }
            } finally {
                main.post(ready)
            }
        }
    }
}
