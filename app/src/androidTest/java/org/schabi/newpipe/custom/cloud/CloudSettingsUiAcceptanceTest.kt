/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.R
import org.schabi.newpipe.custom.tagging.TagRuntime
import org.schabi.newpipe.settings.CloudSyncSettingsFragment
import org.schabi.newpipe.settings.SettingsActivity
import org.schabi.newpipe.util.ThemeHelper

/** Explicit QA only: real connected views, preview cancellation, rotation and credential secrecy. */
class CloudSettingsUiAcceptanceTest {
    @Test fun lightThemeKeepsPreviewAndCredentialsSafe() = runTheme("light", R.string.light_theme_key)

    @Test fun darkThemeKeepsPreviewAndCredentialsSafe() = runTheme("dark", R.string.dark_theme_key)

    @Test fun blackThemeKeepsPreviewAndCredentialsSafe() = runTheme("black", R.string.black_theme_key)

    private fun runTheme(name: String, theme: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit live QA UI only", arguments.getString("cloudUiAcceptance") == "true")
        val app = ApplicationProvider.getApplicationContext<Context>()
        check(app.packageName == "org.schabi.newpipe.debug.qacloudaccept20261021")
        val probe = checkNotNull(arguments.getString("cloudUiProbe"))
        require(probe.matches(Regex("[a-z0-9-]{1,48}")))
        val output = File(app.noBackupFilesDir, "cloud-ui-$probe").also { it.mkdirs() }
        val controller = CloudSyncController(app)
        val before = checkNotNull(controller.connection.read())
        assertFalse("QA must begin with sync disabled", before.enabled)
        assertFalse(controller.hasPending())
        val repository = TagRuntime.get(app)
        val fingerprint = CloudNativeCatalog(repository).capture(before.scope, before.incarnation).fingerprint
        val preferences = PreferenceManager.getDefaultSharedPreferences(app)
        val key = app.getString(R.string.theme_key)
        val old = preferences.getString(key, null)
        check(preferences.edit().putString(key, app.getString(theme)).commit())
        instrumentation.runOnMainSync { ThemeHelper.setDayNightMode(app) }
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                waitFor(scenario) { it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
                scenario.onActivity {
                    it.supportFragmentManager.executePendingTransactions()
                    it.supportFragmentManager.beginTransaction().replace(R.id.settings_fragment_holder, CloudSyncSettingsFragment()).commitNow()
                }
                waitFor(scenario) { model(it).state.value?.let { s -> s.connected && !s.busy } == true && fragment(it).lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && it.window.decorView.hasWindowFocus() }
                scenario.onActivity {
                    val f = fragment(it)
                    assertFalse(f.findPreference<SwitchPreferenceCompat>("cloud_enabled")!!.isChecked)
                    assertFalse(f.findPreference<Preference>("cloud_now")!!.isEnabled)
                    assertTrue(f.findPreference<Preference>("cloud_reconnect")!!.isEnabled)
                }
                screenshot(File(output, "$name-settings-portrait.png"))
                scenario.onActivity {
                    val toggle = fragment(it).findPreference<SwitchPreferenceCompat>("cloud_enabled")!!
                    assertFalse(toggle.onPreferenceChangeListener!!.onPreferenceChange(toggle, true))
                    assertFalse(toggle.isChecked)
                }
                waitFor(scenario) { preview(it)?.isShowing == true && model(it).state.value?.busy == false }
                var previewId: String? = null
                scenario.onActivity {
                    previewId = model(it).state.value!!.previewId
                    assertNotNull(previewId)
                    assertFalse(model(it).state.value!!.enabled)
                    val dialog = checkNotNull(preview(it))
                    assertReachable(dialog.getButton(AlertDialog.BUTTON_POSITIVE))
                    assertReachable(dialog.getButton(AlertDialog.BUTTON_NEGATIVE))
                    descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText("Synthetic")
                }
                screenshot(File(output, "$name-preview-portrait.png"))
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                waitFor(scenario) { it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && preview(it)?.isShowing == true }
                // Rotation changes configuration before the replacement window finishes layout.
                instrumentation.waitForIdleSync()
                Thread.sleep(750)
                screenshot(File(output, "$name-preview-landscape-before-assertion.png"))
                scenario.onActivity {
                    assertEquals(previewId, model(it).state.value!!.previewId)
                    assertEquals("Synthetic", model(it).query)
                    assertFalse(model(it).state.value!!.enabled)
                    val dialog = checkNotNull(preview(it))
                    assertReachable(dialog.getButton(AlertDialog.BUTTON_POSITIVE))
                    assertReachable(dialog.getButton(AlertDialog.BUTTON_NEGATIVE))
                }
                screenshot(File(output, "$name-preview-landscape.png"))
                scenario.onActivity { preview(it)!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
                waitFor(scenario) { model(it).state.value?.previewId == null && preview(it) == null }
                var secretEditor: EditText? = null
                scenario.onActivity {
                    val p = fragment(it).findPreference<Preference>("cloud_reconnect")!!
                    p.onPreferenceClickListener!!.onPreferenceClick(p)
                    val dialog = checkNotNull(credentials(fragment(it)))
                    assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    val editors = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().toList()
                    assertEquals(2, editors.size)
                    assertFalse(editors[0].isEnabled)
                    secretEditor = editors[1]
                    assertFalse(editors[1].isSaveEnabled)
                    assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, editors[1].inputType and InputType.TYPE_MASK_VARIATION)
                    editors[1].setText("synthetic-qa-secret-not-a-credential")
                }
                scenario.recreate()
                waitFor(scenario) { model(it).state.value?.let { s -> s.connected && !s.busy } == true && fragment(it).lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && it.window.decorView.hasWindowFocus() }
                scenario.onActivity {
                    assertNull(credentials(fragment(it)))
                    assertEquals("", secretEditor!!.text.toString())
                    assertFalse(model(it).state.value!!.enabled)
                    assertFalse(fragment(it).findPreference<SwitchPreferenceCompat>("cloud_enabled")!!.isChecked)
                }
            }
            val after = checkNotNull(controller.connection.read())
            assertFalse(after.enabled)
            assertEquals(before.generation, after.generation)
            assertEquals(fingerprint, CloudNativeCatalog(repository).capture(after.scope, after.incarnation).fingerprint)
            File(output, "$name-passed.txt").writeText("preview-cancel-no-mutation;real-rotation;secure-credential-dismissal;sync-off\n")
        } finally {
            check(preferences.edit().apply { if (old == null) remove(key) else putString(key, old) }.commit())
            instrumentation.runOnMainSync { ThemeHelper.setDayNightMode(app) }
            instrumentation.waitForIdleSync()
        }
    }
    private fun fragment(activity: SettingsActivity) = activity.supportFragmentManager.findFragmentById(R.id.settings_fragment_holder) as CloudSyncSettingsFragment
    private fun model(activity: SettingsActivity) = ViewModelProvider(activity)[CloudScreenModel::class.java]
    private fun preview(activity: SettingsActivity) = (activity.supportFragmentManager.findFragmentByTag(CloudChangePreviewDialog.TAG) as? CloudChangePreviewDialog)?.dialog as? AlertDialog
    private fun credentials(fragment: CloudSyncSettingsFragment): AlertDialog? = CloudSyncSettingsFragment::class.java.getDeclaredField("credentialDialog").let {
        it.isAccessible = true
        it.get(fragment) as? AlertDialog
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    private fun assertReachable(view: View) {
        val rect = Rect()
        assertTrue(view.isShown && view.isEnabled && view.getGlobalVisibleRect(rect))
        assertTrue("Confirmation button is clipped: visible=$rect measured=${view.width}x${view.height}; " + generateSequence(view as View?) { it.parent as? View }.joinToString { "${it.javaClass.simpleName} ${it.width}x${it.height} top=${it.top} padding=${it.paddingTop}/${it.paddingBottom}" }, rect.height() >= view.height - 1 && rect.width() >= view.width - 1)
    }
    private fun screenshot(file: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    private fun waitFor(scenario: ActivityScenario<SettingsActivity>, predicate: (SettingsActivity) -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            var ready = false
            scenario.onActivity { ready = predicate(it) }
            if (ready) return
            Thread.sleep(100)
        }
        var details = "unavailable"
        scenario.onActivity { details = "${model(it).state.value}; lifecycle=${fragment(it).lifecycle.currentState}; preview=${preview(it)?.isShowing}" }
        error("Cloud UI did not reach the expected state: $details")
    }
}
