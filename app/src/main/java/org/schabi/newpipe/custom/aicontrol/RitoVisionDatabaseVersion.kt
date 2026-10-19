/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

/**
 * Fork-local Room schema namespace. Official NewPipe schema integers are left untouched.
 *
 * NP9/RV2 = 9002. NP9/RV1 (9001) remains a supported import source.
 * A future upstream integration increments the fork revision monotonically.
 * Fork revisions are monotonic; do not reset the revision on upstream updates.
 */
object RitoVisionDatabaseVersion {
    const val UPSTREAM_SCHEMA = 9
    const val FORK_REVISION = 2
    const val AI_SCHEMA = 9001
    const val NAMESPACE_SCALE = 1000
    const val CURRENT = UPSTREAM_SCHEMA * NAMESPACE_SCALE + FORK_REVISION

    /**
     * Only upstream schemas for which we registered migrations and this exact
     * fork revision can be imported. In particular, the discarded old fork
     * schema 10 is not upstream NP10 and must never be silently accepted.
     */
    fun supportsImportSchema(schemaVersion: Int): Boolean = schemaVersion in 1..UPSTREAM_SCHEMA || schemaVersion == AI_SCHEMA || schemaVersion == CURRENT
}
