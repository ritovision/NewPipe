/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import java.text.DateFormat
import java.util.Date
import org.schabi.newpipe.R
import org.schabi.newpipe.util.ThemeHelper

/** Dedicated first-party setup/status screen. Once paired, the AI chat is the catalog interface. */
class AiControlActivity : AppCompatActivity() {
    private lateinit var runtime: AiControlRuntime
    private lateinit var status: TextView
    private lateinit var name: EditText
    private lateinit var gateway: EditText
    private lateinit var code: EditText
    private lateinit var start: Button
    private lateinit var pair: Button
    private lateinit var save: Button
    private lateinit var connectBrowser: Button
    private lateinit var pendingStatus: TextView
    private lateinit var openBrowser: Button
    private lateinit var cancelSetup: Button
    private var advancedPairing = false
    private var wasPending = false
    private val pollingHandler = Handler(Looper.getMainLooper())
    private val pairingPoll = object : Runnable {
        override fun run() {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
            runtime.pollBrowserPairing()
            pollingHandler.postDelayed(this, 2000)
        }
    }
    private lateinit var clients: LinearLayout
    private val scopes = linkedMapOf<AiScope, CheckBox>()
    private var observation: AutoCloseable? = null
    private var populated = false
    private var busy = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enableAndStart() else message(getString(R.string.rv_ai_notifications_needed))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.setDayNightMode(this)
        ThemeHelper.setTheme(this)
        super.onCreate(savedInstanceState)
        runtime = AiControlRuntime.get(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = Toolbar(this).apply {
            title = getString(R.string.rv_ai_title)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        fun text(value: String): TextView = TextView(this).apply {
            text = value
            textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
            content.addView(this)
        }
        text(getString(R.string.rv_ai_intro))
        status = text(getString(R.string.rv_ai_loading)).apply { setTextIsSelectable(true) }
        start = button(content, getString(R.string.rv_ai_start)) {
            if (runtime.snapshot.state?.enabled == true && runtime.snapshot.connection != "STOPPED") {
                runtime.setEnabled(false) { error -> error?.let(::message) }
            } else if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                if (Build.VERSION.SDK_INT >= 33) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    message(getString(R.string.rv_ai_notifications_needed))
                }
            } else {
                enableAndStart()
            }
        }
        text(getString(R.string.rv_ai_pairing_heading)).apply { textSize = 19f }
        name = field(content, getString(R.string.rv_ai_device_name))
        gateway = field(content, getString(R.string.rv_ai_gateway)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(getString(R.string.rv_ai_default_gateway))
        }
        code = field(content, getString(R.string.rv_ai_pairing_code)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        text(getString(R.string.rv_ai_permissions_heading)).apply { textSize = 19f }
        for (scope in AiScope.entries) {
            val check = CheckBox(this).apply {
                text = getString(scopeLabel(scope))
                setPadding(0, dp(3), 0, dp(3))
            }
            scopes[scope] = check
            content.addView(check)
        }
        text(getString(R.string.rv_ai_permissions_explanation))
        text(getString(R.string.rv_ai_connect_explanation))
        connectBrowser = button(content, getString(R.string.rv_ai_connect_browser)) {
            operation { done ->
                runtime.beginBrowserPairing(gateway.text.toString(), name.text.toString(), permissions()) { error ->
                    done(error)
                    if (error == null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) showPairingBrowserPrompt()
                }
            }
        }
        pendingStatus = text("").apply { setTextIsSelectable(true) }
        openBrowser = button(content, getString(R.string.rv_ai_open_pairing_browser)) { showPairingBrowserPrompt() }
        cancelSetup = button(content, getString(R.string.rv_ai_close_pairing)) {
            AlertDialog.Builder(this).setTitle(R.string.rv_ai_close_pairing)
                .setMessage(R.string.rv_ai_close_pairing_explanation)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.rv_ai_close_pairing) { _, _ -> operation { done -> runtime.cancelBrowserPairing(done) } }.show()
        }
        button(content, getString(R.string.rv_ai_advanced_pairing)) {
            advancedPairing = !advancedPairing
            render(runtime.snapshot)
        }
        pair = button(content, getString(R.string.rv_ai_pair)) {
            operation { done ->
                runtime.pair(gateway.text.toString(), name.text.toString(), code.text.toString(), permissions()) { error ->
                    if (error == null) {
                        code.text.clear()
                        populated = false
                    }
                    done(error)
                }
            }
        }
        save = button(content, getString(R.string.rv_ai_save_permissions)) {
            operation { done -> runtime.configure(name.text.toString(), permissions(), done) }
        }
        button(content, getString(R.string.rv_ai_copy_device)) {
            runtime.snapshot.state?.deviceId?.let {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("NewPipe device ID", it))
                message(getString(R.string.rv_ai_copied))
            }
        }
        text(getString(R.string.rv_ai_clients_heading)).apply { textSize = 19f }
        clients = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            content.addView(this)
        }
        button(content, getString(R.string.rv_ai_forget)) {
            operation { done ->
                runtime.forget { error ->
                    populated = false
                    done(error)
                }
            }
        }
        text(getString(R.string.rv_ai_background_explanation))
        render(runtime.snapshot)
    }

    override fun onStart() {
        super.onStart()
        observation = runtime.observe(::render)
        pollingHandler.post(pairingPoll)
    }

    override fun onStop() {
        pollingHandler.removeCallbacks(pairingPoll)
        observation?.close()
        observation = null
        super.onStop()
    }

    private fun render(snapshot: AiUiSnapshot) {
        if (isFinishing || isDestroyed) return
        val state = snapshot.state
        val pending = snapshot.pendingPairing
        if (wasPending && pending == null && state?.paired == true) populated = false
        wasPending = pending != null
        if (state != null && !populated) {
            val shown = pending?.proposed ?: state
            name.setText(shown.deviceName)
            gateway.setText(shown.gatewayOrigin.ifEmpty { getString(R.string.rv_ai_default_gateway) })
            scopes.forEach { (scope, check) -> check.isChecked = scope.wireName in shown.permissions }
            populated = true
        }
        status.text = buildString {
            append(getString(R.string.rv_ai_status, snapshot.connection.lowercase().replace('_', ' ')))
            if (state != null) {
                append('\n').append(getString(if (state.paired) R.string.rv_ai_paired else R.string.rv_ai_unpaired))
                append('\n').append(state.deviceId)
            }
            snapshot.error?.let { append("\n").append(it) }
            snapshot.lastOperation?.let {
                append("\n\n").append(it).append(": ").append(snapshot.lastOutcome)
                append('\n').append(DateFormat.getTimeInstance().format(Date(snapshot.lastOperationAtMs)))
            }
        }
        start.text = getString(if (state?.enabled == true && snapshot.connection != "STOPPED") R.string.rv_ai_stop else R.string.rv_ai_start)
        start.isEnabled = !busy && state?.paired == true
        connectBrowser.visibility = if (state?.paired == true) View.GONE else View.VISIBLE
        connectBrowser.isEnabled = !busy && state != null && pending == null
        pendingStatus.visibility = if (pending == null) View.GONE else View.VISIBLE
        pendingStatus.text = pending?.let {
            val label = when (it.serverStatus) {
                "approved", "committed", "complete" -> R.string.rv_ai_pairing_completing
                "denied", "cancelled" -> R.string.rv_ai_pairing_denied
                "local" -> R.string.rv_ai_pairing_preparing
                else -> R.string.rv_ai_pairing_waiting
            }
            (if (it.serverStatus == "local") "" else getString(R.string.rv_ai_pairing_comparison, it.comparisonCode) + "\n") + getString(label) +
                (snapshot.pairingError?.let { error -> "\n" + getString(R.string.rv_ai_pairing_problem, error) } ?: "")
        } ?: ""
        openBrowser.visibility = if (pending != null && pending.serverStatus == "pending") View.VISIBLE else View.GONE
        openBrowser.isEnabled = !busy && pending != null && System.currentTimeMillis() < pending.expiresAtMs
        cancelSetup.visibility = if (pending == null) View.GONE else View.VISIBLE
        cancelSetup.isEnabled = !busy
        code.visibility = if (advancedPairing && pending == null) View.VISIBLE else View.GONE
        pair.visibility = code.visibility
        pair.isEnabled = !busy && state != null && pending == null
        save.isEnabled = !busy && state != null && pending == null
        name.isEnabled = !busy && pending == null
        gateway.isEnabled = !busy && pending == null
        scopes.values.forEach { it.isEnabled = !busy && pending == null }
        clients.removeAllViews()
        if (state?.clients.isNullOrEmpty()) {
            clients.addView(TextView(this).apply { text = getString(R.string.rv_ai_no_clients) })
        } else {
            state!!.clients.forEach { (id, label) ->
                clients.addView(
                    TextView(this).apply {
                        text = "$label\n$id"
                        textSize = 13f
                        setTextIsSelectable(true)
                    }
                )
                button(clients, getString(R.string.rv_ai_revoke_client)) { operation { done -> runtime.revokeClient(id, done) } }
            }
        }
    }

    private fun showPairingBrowserPrompt() {
        val pending = runtime.snapshot.pendingPairing ?: return
        if (pending.serverStatus != "pending" || System.currentTimeMillis() >= pending.expiresAtMs) return
        AlertDialog.Builder(this).setTitle(R.string.rv_ai_connect_browser)
            .setMessage(getString(R.string.rv_ai_pairing_browser_prompt, pending.comparisonCode))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rv_ai_open_pairing_browser) { _, _ ->
                val current = runtime.snapshot.pendingPairing
                if (current?.requestId == pending.requestId && current.serverStatus == "pending" &&
                    System.currentTimeMillis() < current.expiresAtMs
                ) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current.approvalUrl)).addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (_: ActivityNotFoundException) {
                        message(getString(R.string.rv_ai_browser_unavailable))
                    }
                }
            }.show()
    }

    private fun enableAndStart() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || busy) return
        operation { done ->
            runtime.setEnabled(true) { error ->
                if (error != null) {
                    done(error)
                } else if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    runtime.setEnabled(false) { done(getString(R.string.rv_ai_start_visible)) }
                } else {
                    try {
                        ContextCompat.startForegroundService(this, Intent(this, AiControlService::class.java).setAction(AiControlService.ACTION_START))
                        done(null)
                    } catch (_: Exception) {
                        runtime.setEnabled(false) { done(getString(R.string.rv_ai_start_visible)) }
                    }
                }
            }
        }
    }

    private fun permissions(): Set<String> = scopes.filterValues { it.isChecked }.keys.map { it.wireName }.toSet()

    private fun operation(action: ((String?) -> Unit) -> Unit) {
        if (busy) return
        busy = true
        render(runtime.snapshot)
        action { error ->
            busy = false
            if (!isFinishing && !isDestroyed) {
                render(runtime.snapshot)
                error?.let(::message)
            }
        }
    }

    private fun button(parent: LinearLayout, label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { if (!busy) action() }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    private fun field(parent: LinearLayout, hint: String): EditText = EditText(this).apply {
        this.hint = hint
        isSingleLine = true
        textSize = 16f
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    private fun message(value: String) {
        if (!isFinishing && !isDestroyed) Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun scopeLabel(scope: AiScope): Int = when (scope) {
        AiScope.TAGS_READ -> R.string.rv_ai_scope_tags_read
        AiScope.TAGS_WRITE -> R.string.rv_ai_scope_tags_write
        AiScope.TAGS_DELETE -> R.string.rv_ai_scope_tags_delete
        AiScope.LIBRARY_READ -> R.string.rv_ai_scope_read
        AiScope.LIBRARY_WRITE -> R.string.rv_ai_scope_save
        AiScope.LIBRARY_DELETE -> R.string.rv_ai_scope_delete
        AiScope.PLAYLIST_WRITE -> R.string.rv_ai_scope_playlists
        AiScope.PLAYLIST_DELETE -> R.string.rv_ai_scope_delete_playlists
        AiScope.PLAYBACK_CONTROL -> R.string.rv_ai_scope_playback
    }
}
