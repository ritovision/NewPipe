/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.tagging

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers

/** Retains an in-flight Apply across rotation without retaining any Activity or View. */
class TagEditorSaveState : ViewModel() {
    sealed interface Result {
        data object Idle : Result
        data object Saving : Result
        data object Saved : Result
        data class Failed(val error: Throwable) : Result
    }
    private val mutableResult = MutableLiveData<Result>(Result.Idle)
    val result: LiveData<Result> = mutableResult
    private var operation: Disposable? = null

    fun apply(action: () -> Unit) {
        if (mutableResult.value == Result.Saving || mutableResult.value == Result.Saved) return
        mutableResult.value = Result.Saving
        operation = Completable.fromAction(action).subscribeOn(Schedulers.io()).subscribe({
            mutableResult.postValue(Result.Saved)
        }, { mutableResult.postValue(Result.Failed(it)) })
    }

    override fun onCleared() {
        operation?.dispose()
        super.onCleared()
    }
}
