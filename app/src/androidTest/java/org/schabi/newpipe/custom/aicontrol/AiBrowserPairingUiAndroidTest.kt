/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.R

/** Never launches on the physical/default display and never initiates network pairing. */
class AiBrowserPairingUiAndroidTest {
    private fun children(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
    private fun capture(activity: AiControlActivity, name: String) {
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val directory = File(activity.filesDir, "ai-pairing-ui").apply { mkdirs() }
        File(directory, name + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun virtualDisplayShowsBrowserSetupAndHidesManualCodeUntilAdvanced() {
        check(BuildConfig.APPLICATION_ID == "org.schabi.newpipe.debug.featureaipasskeys20261109") { "Isolated QA package required" }
        val displayId = InstrumentationRegistry.getArguments().getString("qa_display_id")?.toIntOrNull()
        check(displayId != null && displayId > 0) { "Explicit non-default scrcpy virtual display required" }
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(displayId)?.isValid == true) { "Virtual display is unavailable" }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val intent = Intent(context, AiControlActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<AiControlActivity>(intent, options).use { scenario ->
            var ready = false
            repeat(100) {
                if (!ready) {
                    scenario.onActivity { activity ->
                        ready = children(activity.window.decorView).filterIsInstance<Button>()
                            .any { it.text.toString() == activity.getString(R.string.rv_ai_connect_browser) && it.isEnabled }
                    }
                    if (!ready) Thread.sleep(50)
                }
            }
            assertTrue("Connect to AI never became available", ready)
            scenario.onActivity { activity ->
                assertEquals(displayId, activity.display?.displayId)
                val views = children(activity.window.decorView)
                val status = activity.getString(R.string.rv_ai_unpaired)
                assertTrue(status.contains("No pairing code to copy"))
                assertTrue(views.filterIsInstance<TextView>().any { it.text.toString().contains(status) })
                val manual = views.filterIsInstance<EditText>().single { it.hint.toString() == activity.getString(R.string.rv_ai_pairing_code) }
                assertFalse(manual.isShown)
                val advanced = views.filterIsInstance<Button>().single { it.text.toString() == activity.getString(R.string.rv_ai_advanced_pairing) }
                advanced.performClick()
                assertTrue(manual.isShown)
                advanced.performClick()
                assertFalse(manual.isShown)
                val start = views.filterIsInstance<Button>().single { it.text.toString() == activity.getString(R.string.rv_ai_start) }
                assertFalse(start.isEnabled)
                capture(activity, "top")
                val connect = views.filterIsInstance<Button>().single { it.text.toString() == activity.getString(R.string.rv_ai_connect_browser) }
                connect.requestRectangleOnScreen(Rect(0, 0, connect.width, connect.height), true)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { capture(it, "connect") }
        }
    }
}
