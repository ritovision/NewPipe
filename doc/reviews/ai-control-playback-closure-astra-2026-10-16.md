# Independent review — preserved raw response

Source-only review; reviewer did not code. Author corrections are recorded separately.

**BLOCK** — pinned `f2ab3f6ae719ff31870629d342f773e483aea458` against `2b5108659`. Initial finding **5 remains partially unresolved**; findings **1, 2, 3, 4 and 6 are resolved** in the inspected source.

**P2 — A forgotten completion can still become `EXPIRED/NOT_EXECUTED`.** [AiPlaybackExecutor.kt:61](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/custom/aicontrol/AiPlaybackExecutor.kt:61) measures receipt age from client issue time, while line 93 retains receipts from server admission time. [AiProtocol.kt:345](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/main/java/org/schabi/newpipe/custom/aicontrol/AiProtocol.kt:345) permits five seconds of future clock skew:

- At server time `T`, a request issued at `T+5s` executes successfully.
- Its receipt expires at `T+180s`.
- Retrying at `T+181s` removes the receipt, but calculated request age is only `176s`. Execution falls through to line 65 and reports `EXPIRED/NOT_EXECUTED`, contradicting the completed effect.

[AiPlaybackAuditTest.kt:71](/home/rito/techstuff/ritoverse/newpipe-ai-control/app/src/test/java/org/schabi/newpipe/custom/aicontrol/AiPlaybackAuditTest.kt:71) misses this because issue time equals admission time.

The other original findings are addressed:

| Finding | Pinned resolution |
|---|---|
| 1: Cancellation/binding leaks | `AiMainThreadCall.kt:23,41`; `AiAndroidPlaybackPort.kt:39–49`: pending dispatch cannot execute after cancellation; started work reports uncertainty; cleanup preserves interruption. |
| 2: Wrong queue occurrence | `AiAndroidPlaybackPort.kt:60–64,102–114`; `PlayQueue.java:58,585`: locked identity/revision checks reject stale selections. |
| 3: Binding mistaken for liveness | `AiPlayerRegistry.kt:27–29`; `AiAndroidPlaybackPort.kt:55–59`: lifecycle lookup replaces binding. |
| 4: Skip success without advancing | `AiAndroidPlaybackPort.kt:117–121`: incomplete-tail rejection and observed index change. |
| 6: Lost correlation | `AiPlaybackExecutor.kt:39–41`: correlation retained before argument validation. |

Main-thread lease checks acquire no access lock; mutation process epochs are checked before replay/dispatch. Executor lifetime must remain process-wide. New tests cover basic cancellation, concurrent retries, queue identity and epoch rejection, but not real interruption/timeout races or access-lock contention.

Play/append, authoritative URL resolution and transport remain deferred features, not defects in this increment. Source-only review; no mutable source inspected, changes made, tests/builds run, agents or connectors used.