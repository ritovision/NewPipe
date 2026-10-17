# Astra Max design review adjudication
Scope: pinned AI af902e5a8 + Tagging M1 01ecb133b and .work/integration/contract.md. Verdict DESIGN_BLOCKERS, no runtime execution by reviewer.
1. Accept: receipt lookup across both stores precedes staging, and staging only captures currently saved, exactly verified stream identities. A retry of an earlier deletion must never clear later re-added tags. Implemented in current integration source; pending native regression proof.
2. Accept: staged schema/integrity validation before AI shutdown; shutdown/drain before maintenance lock, never while holding TagRuntime. Implementing import pipeline in Milestone 3. No invalid-import mutation of AI state or installed databases.
3. Accept: durable rollback decision until a commit marker exists; all old DB/tag/binding/WAL/SHM companions and absence preserved. Recovery material retained on failure; before-open guard blocks access until recovery succeeds. Implementing pure filesystem journal + native integration tests.
Not final code approval. Claude unavailable; no Claude verdict is claimed.
