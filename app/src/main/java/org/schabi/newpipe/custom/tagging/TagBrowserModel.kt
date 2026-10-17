/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.TimeUnit

/** Holds the real Library snapshot across rotation; all SQL and filtering runs off the UI thread. */
class TagBrowserModel(application: Application) : AndroidViewModel(application) {
    data class State(
        val snapshot: TagRepository.Snapshot? = null,
        val matching: List<TagRepository.Item> = emptyList(),
        val busy: Boolean = true,
        val error: String? = null
    )

    data class Action(val busy: Boolean = false, val message: String? = null)
    private val mutableAction = MutableLiveData(Action())
    val action: LiveData<Action> = mutableAction
    private var actionTask: Disposable? = null
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    var playlistName = ""

    fun play(keys: List<StreamKey>, append: Boolean) = perform {
        org.schabi.newpipe.custom.aicontrol.AiLocalTagPlayback.play(getApplication(), repository, keys, append) { cancelled.get() }
        if (append) "Added ${keys.size} videos to the active queue" else "Playing ${keys.size} matching videos"
    }

    fun savePlaylist(name: String, keys: List<StreamKey>) = perform {
        repository.createPlaylistSnapshot(name, keys)
        "Saved ${keys.size} videos to $name"
    }

    private fun perform(operation: () -> String) {
        if (mutableAction.value?.busy == true) return
        mutableAction.value = Action(true, "Working…")
        actionTask = Single.fromCallable(operation).subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread()).subscribe({ mutableAction.value = Action(false, it) }, {
                val message = when (it.message) {
                    "NO_ACTIVE_QUEUE" -> "Nothing is playing. Use Play results to start a queue first."
                    "PLAYER_UNAVAILABLE" -> "The player is unavailable. Try Play results again."
                    "QUEUE_CHANGED" -> "The queue changed before this request completed. Review it before retrying."
                    else -> it.message ?: "The operation failed. Refresh before retrying."
                }
                mutableAction.value = Action(false, message)
            })
    }

    val repository = TagRuntime.get(application)
    private val mutableState = MutableLiveData(State())
    val state: LiveData<State> = mutableState
    var filter = TagFilter()
        private set
    val expandedGroups = HashMap<Long, Boolean>()
    var page = 0
    private var snapshot: TagRepository.Snapshot? = null
    private val disposables = CompositeDisposable()
    private var read: Disposable? = null
    private var matching: Disposable? = null
    private var queryGeneration = 0L
    private var readGeneration = 0L
    private var reading = false
    private var refreshAgain = false
    private var active = false

    fun startObserving() {
        if (active) return
        active = true
        // Invalidation has no SQL callback on the writer thread: debounce onto a scheduler.
        disposables.add(
            io.reactivex.rxjava3.core.Observable.create<Unit> { emitter ->
                val observer = object : androidx.room.InvalidationTracker.Observer("playlist_stream_join", "streams", "playlists") {
                    override fun onInvalidated(tables: Set<String>) {
                        if (!emitter.isDisposed) emitter.onNext(Unit)
                    }
                }
                repository.library.invalidationTracker.addObserver(observer)
                emitter.setCancellable { repository.library.invalidationTracker.removeObserver(observer) }
            }.subscribeOn(Schedulers.io()).unsubscribeOn(Schedulers.io()).debounce(150, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread()).subscribe({ refresh() }, { showError(it) })
        )
        disposables.add(
            TagRuntime.observeChanges().debounce(80, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread()).subscribe({ refresh() }, { showError(it) })
        )
    }

    fun stopObserving() {
        active = false
        refreshAgain = false
        disposables.clear()
    }

    fun refresh() {
        if (reading) {
            refreshAgain = true
            return
        }
        val generation = ++readGeneration
        ++queryGeneration
        matching?.dispose()
        reading = true
        mutableState.value = (mutableState.value ?: State()).copy(busy = true, error = null)
        read = Single.fromCallable { repository.snapshot() }
            .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe({ loaded ->
                if (generation != readGeneration) return@subscribe
                reading = false
                snapshot = loaded
                val valid = loaded.definitions.tags.mapTo(HashSet()) { it.id }
                filter = filter.copy(included = filter.included.intersect(valid), excluded = filter.excluded.intersect(valid))
                render()
                if (refreshAgain && active) {
                    refreshAgain = false
                    refresh()
                }
            }, { error ->
                if (generation == readGeneration) {
                    refreshAgain = false
                    showError(error)
                }
            })
    }

    fun setFilter(value: TagFilter) {
        filter = value
        page = 0
        render()
    }

    private fun render() {
        val source = snapshot ?: return
        val selected = filter
        val generation = ++queryGeneration
        matching?.dispose()
        mutableState.value = (mutableState.value ?: State()).copy(snapshot = source, busy = true, error = null)
        matching = Single.fromCallable {
            selected.select(source.items)
        }.subscribeOn(Schedulers.computation()).observeOn(AndroidSchedulers.mainThread()).subscribe({ rows ->
            if (generation == queryGeneration) mutableState.value = State(source, rows, reading)
        }, { error -> if (generation == queryGeneration) showError(error) })
    }

    private fun showError(error: Throwable) {
        reading = false
        mutableState.value = (mutableState.value ?: State()).copy(busy = false, error = error.message ?: "Library unavailable. Retry.")
    }

    override fun onCleared() {
        cancelled.set(true)
        actionTask?.dispose()
        read?.dispose()
        matching?.dispose()
        disposables.dispose()
        super.onCleared()
    }
}
