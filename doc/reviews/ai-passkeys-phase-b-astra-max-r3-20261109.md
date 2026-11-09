**Verdict: approve source candidate `44711d025` relative to `20b07dd5a` within this targeted review. R2 CSP finding: resolved. No new blocking findings.**

The [helper](/home/rito/techstuff/ritoverse/newpipe-ai-passkeys-20261109/gateway/src/owner-web.ts:54) excludes wildcard hosts and CSP delimiters from interpolated origins. It runs [before `beginConsent`](/home/rito/techstuff/ritoverse/newpipe-ai-passkeys-20261109/gateway/src/auth.ts:32), and [HTML construction revalidates](/home/rito/techstuff/ritoverse/newpipe-ai-passkeys-20261109/gateway/src/owner-web.ts:73) the stored canonical origin for initial and resumed consent. No remaining CSP injection path or introduced security regression was found. Provider callback validation remains intact, including its existing loopback-port rule; the original redirect URI remains authoritative.

The added tests are substantive by inspection:

- [Public DCR regressions](/home/rito/techstuff/ritoverse/newpipe-ai-passkeys-20261109/gateway/test/passkeys.test.mjs:338) register crafted callbacks and verify rejection, alongside accepted-origin cases.
- [Failure injection](/home/rito/techstuff/ritoverse/newpipe-ai-passkeys-20261109/gateway/test/fixtures/kv-fault-worker.mjs:20) clears the cached helper so grant issuance reaches the failing KV. The [test](/home/rito/techstuff/ritoverse/newpipe-ai-passkeys-20261109/gateway/test/passkeys.test.mjs:415) checks failure after claim, replay rejection, and successful fresh consent.
- The authorizer/session mismatch and concurrent enrollment/revocation and logout/claim tests exercise actual state transitions and ordering. Fault hooks remain confined to test fixtures; none were found in production source or the existing bundle.

**Independent verification:** inspected the diff, updated adjudication, callsites, pinned provider implementation, tests, and entrypoints; `git diff --check` passed. Read-only probes against the extracted helper passed 29 rejection cases, 13 accepted-origin cases, and 2,660 character probes. I did **not** rerun project tests, builds, or Chromium.

**Separate acceptance limits:** header assertions do not establish browser navigation compatibility, including IPv6. Exact-commit full-suite and Chromium rerun results remain unverified here. Source approval does not waive those gates, real Pixel biometric validation, live ChatGPT acceptance, or production deployment authorization.
