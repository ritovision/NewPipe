# AI Control Phase C — review adjudication

**Date:** 2026-11-09. **Source candidate:** `f7c33bcdc`, relative to accepted gateway baseline `128d33b03`. The main assistant implemented; GPT-6 Astra at **max** reasoning performed read-only reviews. Claude was not required or resumed. These are source reviews, not claims of deployment or physical biometric acceptance.

## Review rounds

- [R1 raw report](ai-passkeys-phase-c-astra-r1-20261109.md): request changes, three P2 findings.
- [R2 raw report](ai-passkeys-phase-c-astra-r2-20261109.md): approves the corrected source candidate, with no concrete remaining blockers in the requested scope. R2 did not run tests; independently recorded execution evidence is required.

An initial attempted invocation used the unsupported model slug `gpt-6` and failed before reviewing. The actual review rounds used the locally advertised `gpt-6-astra` model with `model_reasoning_effort="max"`; no lower-strength fallback was substituted.

## Accepted and fixed findings

### 1. Anonymous cancelled requests could exhaust enrollment capacity

The original eviction policy counted only unviewed pending requests. Signed anonymous begin/cancel churn could create non-evictable cancelled records and occupy the entire bounded account store without owner approval.

**Correction:** anonymous pending and cancelled records share the same 32-entry budget. Eviction never targets owner-viewed, approved or committed records. A 70-cycle churn regression proves bounded storage, protected owner work and fresh-request availability. Cancellation replay safety is handled together with the next correction, rather than by simply forgetting cancellation records.

### 2. Evicted signed begin could recreate its ID with a later deadline

Removing an anonymous pending record allowed its same valid signature/timestamp to be accepted again during the clock-skew window, creating a renewed five-minute deadline.

**Correction:** a public nonce preflight carries a persisted admission generation plus randomness. Unknown begin requires the current generation, signed in the device transcript. Any anonymous eviction atomically rotates the generation with record changes. Evicted pending/cancelled signatures cannot recreate those IDs; retained records still use their exact stored expiry. Android persists this nonce before begin and may change its request-admission binding only in that explicit preflight step. Regression tests cover both rejected evicted replays and retained owner-approved requests across generation rotation.

This is not a bearer authorization credential. Owner approval, device signature, timestamp, scope and origin checks still apply. Fetching a nonce does not allocate a per-request record or rotate the generation.

### 3. HTML/empty transient HTTP errors could stop native recovery

The initial transport parsed JSON before inspecting status, so an intermediary's 429/502/503 page became a terminal invalid-message error instead of a retryable failure.

**Correction:** classify 429, 408 and server errors before reading/parsing any body. Parse successful responses strictly. Permanent errors remain terminal and bounded. Unit tests verify that transient responses never invoke the body parser and malformed successful responses still fail.

## Additional invariants and evidence added

- Original request/policy/generation admission is captured at transaction creation/recovery, not from the current generation at a later queued dispatch. A deterministic Android test queues polling before cancellation cleanup and verifies the old work cannot borrow the cancellation generation.
- Local completion and cancellation use a consistent policy-lock then pairing-commit-lock order. Cancellation persists a local tombstone before deleting staging. Old staging cannot replace a newly paired active state or a changed restore epoch.
- Native tests use real Android Keystore and AtomicFile but isolated no-backup storage and an injected synthetic transport. A strict package guard prevents execution against canonical NewPipe.
- Cross-object gateway failure fixtures exercise registration failure before write, response loss after write, and revocation before/after DeviceRoom registration. Recovery concerns an already committed authorization; it does not invent fresh owner consent after logout. Revocation still wins.
- Browser acceptance includes the actual bundled JavaScript, passkey return to the exact signed request, explicit comparison, denial without approval, and logout preventing uncommitted enrollment.

## Acceptance boundary

The source-level findings are closed by R2. Build, unit, browser and Pixel execution results are recorded separately in the Phase C validation record. Virtual authenticators and injected native transports must not be described as physical fingerprint/PIN or full live gateway-to-phone acceptance. Production deployment, real credential-manager enrollment, real ChatGPT reconnect/refresh and release-toolchain advisory handling remain explicit release gates.
