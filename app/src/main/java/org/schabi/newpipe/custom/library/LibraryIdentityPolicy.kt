/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.library

/** Pure UI policy: names can withhold operations while unbound, never grant authority. */
internal object LibraryIdentityPolicy {
    fun protects(canonicalId: Long?, playlistId: Long?, name: String?): Boolean = if (canonicalId != null) {
        canonicalId == playlistId
    } else {
        name?.trim()?.equals(LibraryBindingStore.LIBRARY_NAME, ignoreCase = true) == true
    }
}
