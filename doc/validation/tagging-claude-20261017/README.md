# Tagging + AI — reviewed source and QA acceptance

**FINAL OWNER SIGN-OFF — OCTOBER 17, 2026: FEATURE ACCEPTED.** The owner waived additional manual testing and the undemonstrated live AI tagging command in order to close this development milestone. Those tests remain **not performed**, not PASS. Main-app physical installation remains a separate optional rollout. See [ACCEPTANCE.md](ACCEPTANCE.md) for the acceptance decision, evidence and boundaries.


**Implementation and source-review work completed.** Production main-app installation and a live authorized AI-tag command remain separate rollout gates; neither is inferred from tests.

## Evidence

| Check | Executed result |
| --- | --- |
| JVM unit tests | 308/308 PASS |
| Gateway tests | 56/56 PASS |
| Native Android integration/regression suite | 153/153 PASS on owned API34 emulator-5574 |
| Strengthened backup assertions | 13/13 PASS; same backup cases rerun, not extra unique tests |
| Actual host force-stop/restart recovery | 5/5 PASS: prepared, main DB, tag DB, binding, commit marker |
| Claude Opus Max UI/API closure | PASS WITH NONBLOCKERS |
| Claude Opus Max data closure | PASS WITH NONBLOCKERS; runtime follow-up tests passed |
| Newtest Pixel installation | Success, same package and signer, no uninstall/data clear |
| Pixel smoke | Cold start succeeded; real persistent Tags page rendered |
| Gateway rollout | Deployed version 75645ddd-3e81-4c9e-b4d0-a04f517a9b4c, existing vars/secrets/grants retained |

Runtime source candidate: `c8041a26911e174887c5e6ce540a70627943b979`. Later commits strengthen test assertions and preserve these records without changing the shipped runtime bytes. `candidate.json` pins both the full-suite and strengthened-test APK hashes/sizes.

Main Room stays **NP9/RV1 / 9001**; tag metadata stays independently versioned **2**. The schema4 legacy backup regression imports through the real pipeline, verifies its subscription and version, then exports/revalidates. The held-writer regression now requires the injected IOException AFTER the swap journal was published, proves the writer was still admitted at shutdown, and verifies rollback includes its committed record. Cached statement tests require the gate's CancellationException, not a generic closed-pool error. No user data was used in destructive tests.

## Rollout boundary

The installed **newtest** is no longer the synthetic-data prototype: its Tags page reads/writes that installation's real Library. Its Library is separate from production customdev and stock/F-Droid. Those production packages and their data were not replaced, uninstalled or cleared. Destructive native tests ran exclusively on the owned emulator.

The reviewed gateway extension is deployed. A direct HTTP smoke client was refused by Cloudflare's edge with **1010 / HTTP403** (browser-signature denial); no bypass or security-setting change was attempted. The existing authorized **newpipe1** connection successfully performed account-level device discovery afterward. Its paired **Pixel 7 was offline**. No actual live tag write through ChatGPT has been demonstrated in this turn. Existing grants were not widened and newtest was not silently paired or activated. The next live acceptance check needs an online paired installation advertising tags and explicit tag read/write/delete permission approval through the normal connection flow.

## Source provenance

Original reviews, follow-up closure reports and issue-by-issue dispositions are in `doc/reviews/tagging-claude-*`. Raw successful build, gateway, native and process-stop output lives beside this file. Earlier failing development iterations are documented in adjudication and preserved privately in the coordinator cache; none is relabeled as a successful run.

The original Library-only tagging branch and earlier UI spike remain recovery points. All integrated source is preserved on `integration/tagging-ai-20261017`; current Git refs show whether main has been fast-forwarded to this reviewed source.
