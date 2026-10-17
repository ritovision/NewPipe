/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.jakewharton.processphoenix.ProcessPhoenix
import java.util.concurrent.Executors
import org.schabi.newpipe.R
import org.schabi.newpipe.settings.NewPipeSettings
import org.schabi.newpipe.streams.io.StoredFileHelper

/** Transfer operations survive rotation. No SQL, ZIP parsing, locking or file copy on main. */
class CatalogTransferModel(application: Application) : AndroidViewModel(application) {
    sealed class State {
        data object Idle : State()
        data class Working(val message: String) : State()
        data class ChooseSettings(val json: Boolean, val legacy: Boolean) : State()
        data class Done(val message: String) : State()
        data class Failed(val error: Throwable) : State()
    }
    private val mutable = MutableLiveData<State>(State.Idle)
    val state: LiveData<State> = mutable
    private var prepared: TagBackupCoordinator.Prepared? = null
    private var importUri: Uri? = null

    @Volatile private var cleared = false
    private val app = application.applicationContext
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(app)

    fun export(file: StoredFileHelper, uri: Uri) {
        if (mutable.value is State.Working || prepared != null) return
        mutable.value = State.Working("Creating a consistent Library and tags backup…")
        worker.execute {
            try {
                TagBackupCoordinator.export(app, prefs, file)
                remember(uri)
                mutable.postValue(State.Done("Library, playlists, tags and settings exported"))
            } catch (error: Exception) {
                mutable.postValue(State.Failed(error))
            }
        }
    }

    fun prepareImport(file: StoredFileHelper, uri: Uri) {
        if (mutable.value is State.Working || prepared != null) return
        mutable.value = State.Working("Validating the backup before changing anything…")
        importUri = uri
        worker.execute {
            try {
                val candidate = TagBackupCoordinator.prepare(app, file)
                main.post {
                    if (cleared) {
                        worker.execute { candidate.close() }
                    } else {
                        prepared = candidate
                        if (candidate.hasJsonPreferences || candidate.hasLegacyPreferences) {
                            mutable.value = State.ChooseSettings(candidate.hasJsonPreferences, candidate.hasLegacyPreferences)
                        } else {
                            install(false)
                        }
                    }
                }
            } catch (error: Exception) {
                mutable.postValue(State.Failed(error))
            }
        }
    }

    fun install(withPreferences: Boolean) {
        val candidate = prepared ?: return
        prepared = null
        mutable.value = State.Working("Restoring Library and tags; the app will restart…")
        worker.execute {
            try {
                candidate.use {
                    // Parse optional settings before stopping AI or replacing files.
                    val settings = if (withPreferences) TagBackupCoordinator.readPreferences(candidate) else null
                    TagBackupCoordinator.install(app, candidate)
                    if (settings != null) {
                        TagBackupCoordinator.applyPreferences(prefs, settings)
                        val tunneling = app.getString(R.string.disable_media_tunneling_key)
                        val automatic = app.getString(R.string.disabled_media_tunneling_automatically_key)
                        if (prefs.getBoolean(tunneling, false) && prefs.getInt(automatic, -1) == 1) {
                            prefs.edit().putInt(automatic, -1).putBoolean(tunneling, false).commit()
                            NewPipeSettings.setMediaTunneling(app)
                        }
                    }
                    importUri?.let(::remember)
                }
            } catch (error: Exception) {
                mutable.postValue(State.Failed(error))
            } finally {
                // A successful or rolled-back swap has closed/inactivated old handles.
                // Restart even if the Activity rotated or settings couldn't be restored.
                if (TagRuntime.restoring) main.post { ProcessPhoenix.triggerRebirth(app) }
            }
        }
    }

    fun cancelPrepared() {
        prepared?.let { candidate ->
            prepared = null
            worker.execute { candidate.close() }
        }
        mutable.value = State.Idle
    }
    fun consumed() {
        if (mutable.value is State.Done || mutable.value is State.Failed) mutable.value = State.Idle
    }
    private fun remember(uri: Uri) {
        prefs.edit().putString(app.getString(R.string.import_export_data_path), uri.toString()).commit()
    }
    override fun onCleared() {
        cleared = true
        cancelPrepared()
    }

    companion object {
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "NewPipe-catalog-transfer").apply { isDaemon = true } }
        private val main = Handler(Looper.getMainLooper())
    }
}
