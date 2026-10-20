1. **P2 — An overlapping backup preview can leave replacement permanently pending.**  
   **Files/functions:** `CloudReplacementController.kt:167–189` (`confirm()`), `:294–301` (`cancel()`); `CloudRecoveryController.kt`, `prepareUpload()`.

   **Trigger:** Prepare replacement A, dismiss its dialog with Back—which retains the activity-scoped preview—then prepare an ordinary cloud backup B. B overwrites the backend recovery approval without changing the connection generation or creating a pending upload. Confirming A therefore passes the local checks, persists its pending files, and fails reservation because A’s recovery approval is gone.

   **Consequence:** “Stop this attempt” retires A’s incoming sequence, but backend abandonment then fails with `RECOVERY_APPROVAL_UNAVAILABLE`. Android never reaches `finish()`. Subsequent resume, resolve and stop cannot clear the marker; new replacement, sync reconciliation, restore and forget remain blocked.

   **Fix direction:** Support cancellation of an unadmitted, superseded approval using its persisted incoming identity. Establish retirement and absence of a matching unresolved allocation before clearing staging; cleanup must not require the obsolete approval row.

2. **P2 — Canceling an unadmitted retry can allow forgetting an unresolved older attempt.**  
   **Files/functions:** `CloudReplacementController.kt:199–202` (`finish()`), `:294–313` (`cancel()`); `CloudSyncController.kt:77–92` (`forget()`), `:62` (`connect()`).

   **Trigger:** Resolve attempt A to STALE, confirm retry B, and lose the request before B’s reservation reaches the backend. Stopping B retires B and fences its recovery sequence, while A correctly remains STALE. Android unconditionally clears B’s staging, including the marker that replaced A’s durable record.

   **Consequence:** Discovery reports A as pending, but `forget()` checks only local files and permits credential removal. Reconnecting with that token then fails because `connect()` rejects unresolved replacements. A’s library-wide replacement/history protections remain until cleanup outside this connection.

   **Fix direction:** Preserve a durable reference to the surviving STALE attempt when canceling an unadmitted retry, and prevent forgetting while an authoritative ACTIVE/STALE attempt remains.

**Unproven acceptance limits**

- Live replacement against Worker/R2—including rotation, dirty-device epoch handling and outgoing-archive restore—is still pending.
- Controller recreation tests do not demonstrate actual process death during staging, reservation, publication or cleanup. Two physical devices and OS network outages remain unproven.
- Complete preview enumeration/search cannot be independently established from these call sites: `CloudChangeExplorer` is not included, and a completed replacement UI walkthrough is not supplied.

**REQUEST_CHANGES**