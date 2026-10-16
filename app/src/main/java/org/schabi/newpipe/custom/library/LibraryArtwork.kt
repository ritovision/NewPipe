/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.library

import android.view.View
import android.widget.ImageView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.target
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.util.image.CoilHelper

/** Local-only artwork, independent of the stream thumbnail schema and image-download preference. */
object LibraryArtwork {
    @JvmStatic
    fun load(target: ImageView, playlistId: Long, url: String?) {
        clearBinding(target)
        val binding = Binding(target, playlistId, url)
        target.setTag(R.id.rv_library_artwork_binding, binding)
        target.addOnAttachStateChangeListener(binding)
        binding.renderCurrent()
        if (target.isAttachedToWindow) binding.connect()
    }

    /** The tab picker recycles the same holder between local and remote playlists. */
    @JvmStatic
    fun loadRemote(target: ImageView, url: String?) {
        clearBinding(target)
        CoilHelper.loadPlaylistThumbnail(target, url)
    }

    private fun clearBinding(target: ImageView) {
        (target.getTag(R.id.rv_library_artwork_binding) as? Binding)?.close()
        target.setTag(R.id.rv_library_artwork_binding, null)
    }

    private class Binding(
        private val target: ImageView,
        private val playlistId: Long,
        private val url: String?
    ) : View.OnAttachStateChangeListener {
        private val db = NewPipeDatabase.getInstance(target.context)
        private var subscription: Disposable? = null
        private var shownAsLibrary: Boolean? = null

        fun renderCurrent() = render(LibraryRepository.isBoundLibrary(db, playlistId))

        fun connect() {
            subscription?.dispose()
            // Keep the rendered identity across reattachment. Coil already
            // resumes its request; only a changed identity needs a new one.
            subscription = LibraryRepository.observeLibraryId(db)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ render(it == playlistId) }, { render(false) })
        }

        private fun render(canonical: Boolean) {
            if (shownAsLibrary == canonical) return
            shownAsLibrary = canonical
            if (canonical) {
                // Coil replaces any previous request for this recycled ImageView,
                // so a late video image cannot overwrite the fixed Library artwork.
                target.context.imageLoader.enqueue(
                    ImageRequest.Builder(target.context)
                        .data(R.drawable.rv_library_thumbnail)
                        .target(target)
                        .build()
                )
            } else {
                CoilHelper.loadPlaylistThumbnail(target, url)
            }
        }

        fun close() {
            subscription?.dispose()
            subscription = null
            target.removeOnAttachStateChangeListener(this)
        }

        override fun onViewAttachedToWindow(view: View) = connect()

        override fun onViewDetachedFromWindow(view: View) {
            subscription?.dispose()
            subscription = null
        }
    }
}
