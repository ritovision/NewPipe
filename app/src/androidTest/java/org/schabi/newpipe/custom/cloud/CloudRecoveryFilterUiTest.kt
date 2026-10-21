/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.custom.tagging.CatalogFileSwap
import org.schabi.newpipe.settings.SettingsActivity

/** Actual retained DialogFragment/view recreation; no live service or installed catalog mutation. */
class CloudRecoveryFilterUiTest {
    @Test fun recoveryFilterTextAndRowsStayInAgreementAcrossRotationAndCancellation() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        check(app.packageName == "org.schabi.newpipe.debug.featurecloudsyncv120261019")
        val root = Files.createTempDirectory(app.noBackupFilesDir.toPath(), "recovery-filter-qa-")
        val f = CloudApplyFixture(app, root)
        try {
            f.initialize()
            val original = f.seed()
            val peer = CloudFakeRecoveryPeer(CloudFakePeer(f.scope))
            peer.catalog.external(original.catalog)
            val sync = CloudSyncController(f.context, CloudConnectionStore(f.context), { f.repo }, peer::client, { _: CloudNativeSnapshot -> })
            sync.connect("https://example.test", peer.catalog.tokenA)
            val recovery = CloudRecoveryController(f.context, sync, peer::client)
            recovery.confirmUpload(recovery.prepareUpload("cloud-catalog"))
            f.media("Filter-new-local")
            val category = f.repo.definitions().categories.first()
            f.repo.renameCategory(category.id, "Filter changed category")
            val preview = recovery.prepareRestore(peer.copies.single().id)
            val groups = CloudChangeExplorer.groups(preview.before.catalog, preview.decoded!!.catalog, preview.before.hints)
            assertTrue(groups.size >= 2)
            val filter = "Filter-new-local"
            val expected = CloudChangeExplorer.search(groups, filter)
            assertTrue(expected.isNotEmpty() && expected.size < groups.size)
            val calls = peer.catalog.calls
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    val model = ViewModelProvider(activity)[CloudRecoveryScreenModel::class.java]
                    setField(model, "restore", preview)
                    setField(model, "groups", groups)
                    @Suppress("UNCHECKED_CAST")
                    val mutable = model.state as MutableLiveData<CloudRecoveryScreenModel.Screen>
                    mutable.value = CloudRecoveryScreenModel.Screen(preview = preview.id, confirm = "Restore on this device", loaded = true)
                    model.search(filter)
                    CloudRecoveryDialog().showNow(activity.supportFragmentManager, CloudRecoveryDialog.TAG)
                }
                await(scenario) { activity -> editor(activity)?.text?.toString() == filter }
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[CloudRecoveryScreenModel::class.java]
                    assertEquals(filter, model.query)
                    assertEquals(expected.map { it.id }.toSet(), model.details.value!!.map { it.group }.toSet())
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                await(scenario) { activity -> activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && editor(activity)?.text?.toString() == filter }
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[CloudRecoveryScreenModel::class.java]
                    assertEquals(filter, model.query)
                    assertEquals(expected.map { it.id }.toSet(), model.details.value!!.map { it.group }.toSet())
                    editor(activity)!!.setText("")
                    assertEquals(groups.map { it.id }.toSet(), model.details.value!!.map { it.group }.toSet())
                    editor(activity)!!.setText(filter)
                    model.cancel()
                }
                await(scenario) { activity -> ViewModelProvider(activity)[CloudRecoveryScreenModel::class.java].state.value?.preview == null }
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[CloudRecoveryScreenModel::class.java]
                    assertEquals("", model.query)
                    assertEquals("", editor(activity)!!.text.toString())
                }
            }
            assertEquals(calls, peer.catalog.calls)
            assertFalse(sync.connection.read()!!.enabled)
            assertEquals(preview.before.fingerprint, f.capture().fingerprint)
        } finally {
            f.close()
            CatalogFileSwap.deleteTree(root)
        }
    }
    private fun setField(model: CloudRecoveryScreenModel, name: String, value: Any) {
        CloudRecoveryScreenModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(model, value)
    }
    private fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(views(root.getChildAt(i)))
    }
    private fun editor(activity: SettingsActivity): EditText? {
        val dialog = (activity.supportFragmentManager.findFragmentByTag(CloudRecoveryDialog.TAG) as? CloudRecoveryDialog)?.dialog
        return dialog?.window?.decorView?.let { views(it).filterIsInstance<EditText>().firstOrNull() }
    }
    private fun await(scenario: ActivityScenario<SettingsActivity>, condition: (SettingsActivity) -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            scenario.onActivity { ready = condition(it) }
            if (ready) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                return
            }
            Thread.sleep(100)
        }
        error("RECOVERY_FILTER_UI_TIMEOUT")
    }
}
