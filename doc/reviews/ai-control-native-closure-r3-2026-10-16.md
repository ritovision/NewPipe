# Targeted native verification — raw response

The four follow-up findings were accepted and corrected by the main author. Subsequent verification is separate.

**BLOCK**

- **P2 — Cancellation leaves the shared PoToken cache incomplete.** [PoTokenProviderImpl.kt:81](/home/rito/.cache/newpipe-ai-control/native-review-closure-r3/PoTokenProviderImpl.kt:81) publishes the generator before obtaining the streaming token at line 85. Cancellation there leaves a nonexpired generator with a null or previous token; ordinary NewPipe requests then reuse it and can fail at line 91. Publish the complete cache together after success; close unpublished replacements.

- **P2 — PoToken lock acquisition remains unbounded and uncancellable.** [PoTokenProviderImpl.kt:59](/home/rito/.cache/newpipe-ai-control/native-review-closure-r3/PoTokenProviderImpl.kt:59) holds a monitor across token initialization. A normal caller can hold it indefinitely through `blockingGet()`, leaving AI extraction stuck before its cancellation checks and preventing restore’s command drain. Use deadline-aware, cancellable acquisition for AI callers.

- **P2 — Restore discards previously queued revocations.** [AiControlRuntime.kt:198](/home/rito/.cache/newpipe-ai-control/native-review-closure-r3/AiControlRuntime.kt:198) skips persistence when `beforeRestore()` sets `restoring` at line 476. A revoke queued beforehand therefore never enters `blockedClients`; same-owner pairing after restart permits that client again. Persist revocations admitted before maintenance before completing the restore barrier.

- **P3 — Cancelled initialization can leave an unpublished WebView open.** [PoTokenWebView.kt:179](/home/rito/.cache/newpipe-ai-control/native-review-closure-r3/PoTokenWebView.kt:179) sets `initializedSuccessfully` even if the emitter was cancelled. If this callback runs before queued cleanup at line 395, `onSuccess` is ignored and cleanup skips destruction. Track successful ownership transfer, and close instances whose result was discarded.