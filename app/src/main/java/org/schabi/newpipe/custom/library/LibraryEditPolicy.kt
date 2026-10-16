/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.library

/** Preserve order and deliberate duplicates, admitting only canonically saved items. */
internal object LibraryEditPolicy {
    fun keepSaved(proposedIds: List<Long>, isSaved: (Long) -> Boolean): List<Long> = proposedIds.filter(isSaved)
}
