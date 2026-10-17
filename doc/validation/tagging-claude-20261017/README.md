# Tagging + AI: Claude adjudication and execution evidence

Runtime source candidate: `c8041a26911e174887c5e6ce540a70627943b979`.
Branch: `integration/tagging-ai-20261017`.

- Main Room remains **NP9/RV1 (9001)**. Independent tag database version **2**; category deletion never removes Library items or ordinary playlists.
- **308 JVM tests PASS**, **56 gateway tests PASS**, Java/Kotlin style and both APK builds PASS.
- **153 native Android tests PASS** on owned API34 `emulator-5574`, all synthetic data. Includes real upstream4 import/re-export, stale popup/draft conflicts, predictable AI rejections, pending Room-writer rollback, cached statement fencing, native themed UI, player queue regressions and 20,000-item filtering.
- **5/5 real host force-stop recovery checkpoints PASS**: before first replacement (prepared journal), after main DB, after tag DB, after binding, after commit marker. Each verification launches a new process, uses normal Room/Library startup, checks database consistency and correct old/new binding/content. No physical user app was force-stopped for destructive tests.
- **Claude Opus Max UI/API closure: PASS WITH NONBLOCKERS**. Original reports and adjudications are in `doc/reviews/tagging-claude-*`. Data-helper closure is still pending at this checkpoint; do not substitute a unit-test pass for that independent verdict.
- **Gateway deployed** with existing bindings, secrets and grants preserved. Version `75645ddd-3e81-4c9e-b4d0-a04f517a9b4c`. Public live smoke requests from the test HTTP client received Cloudflare edge error **1010 / HTTP403** (browser-signature denial), not a verified MCP command result. No attempt was made to bypass it or fabricate a live command pass. New tag permissions still require normal explicit consent, and the target installation must advertise the new capabilities.
- Production customdev and stock/F-Droid APKs/data are not modified. Newtest QA install acceptance is recorded separately after verification.

`candidate.json` pins exact APK hashes/sizes. `native-full-r5.log`, `process-death-results.log` and the per-checkpoint logs are actual primary-machine output, copied only after completion. Build/gateway logs are actual build-machine output. Earlier failing development iterations remain privately available in `.work/claude-fixes` / primary coordinator cache and are explained in the adjudication; none is relabeled a pass.

Remaining completion gates at this checkpoint: data-review closure, newtest physical smoke check, and live authorized AI tag commands. No credentials, tokens or synthetic permission grants are created to bypass normal connection consent.
