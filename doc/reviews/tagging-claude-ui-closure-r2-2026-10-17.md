**VERDICT: PASS WITH NONBLOCKERS** on frozen `c8041a26`, from source only. R1 and R2 are both fixed in the current code, and the Unicode change matches what you described. This is not deployment approval, and I'm not claiming any test results.

## R1 (stale popup reopening with a fresh revision): CLOSED
- **Snapshot capture:** `TagManagerFragment.kt:158` saves `val displayed = definitions` when a row group is drawn. That group's tags come from it (`:159`), and the category comes from the same render loop (`:145`), so both are the same snapshot.
- **All three form entry points use it:** category Rename (`:173`), tag Rename/move (`:191`) and the collapsed chip (`:209`) each pass `displayed` with the object captured in that same render. `TagForms.kt:21,30-32` therefore gets the name, category and revision from one snapshot.
- **What happens on a concurrent change:** a live refresh (`:102-103` → `:125`) now only replaces the field. A popup that is still open opens a form whose revision matches its values. A concurrent change therefore fails `TagStore.kt:140` and shows the conflict screen instead of overwriting.
- **Create buttons:** "+ Category" and "+ Tag" (`:64-65`) still read the live field. That's safe because the create path has no revision check.
- **Popup cleanup:** `onDestroyView` dismisses the tracked popup (`:258-262`).
- **The new test catches the old bug:** `TagFormLifecycleTest.kt:149-174` waits until the Manage list shows the concurrent rename (`:164`) before picking "Rename or move" (`:167`). It then asserts the conflict message and that the stored name is unchanged (`:171-173`). With the old code, Save would have succeeded and the wait at `:171` would time out.

## R2 (legacy names turning AI create/update into OUTCOME_UNKNOWN): CLOSED
- **Stored names are no longer re-validated:** `AiTagExecutor.kt:87,89` compare them with `TagNames.storedKey` (`TagModels.kt:42`), which only trims and lowercases. That gives the same value as the `name_key` written by `TagStore.kt:249-252`.
- **New names are still validated twice:** once at `AiTagCommand.kt:83` (`INVALID_ARGUMENT`, `AiProtocol.kt:452`) and again at `AiTagExecutor.kt:81-85` (`INVALID_NAME`).
- **No other path validates stored names:** the only other `clean`/`key` callers are the native draft (`TagDefinitionDialog.kt:96`) and writes of new rows (`TagStore.kt:250-251`).
- **The new test catches the old bug:** `AiTagIntegrationTest.kt:335-345` inserts raw rows: a tag name with a soft hyphen and a category name with an RLO character. Unrelated creates commit, and case-insensitive duplicates are rejected with the exact codes. Without `storedKey`, the first assertion would get `OUTCOME_UNKNOWN`.

## Unicode nonblocker: resolved as described
- **Kotlin:** `TagModels.kt:37-39` checks code points. It allows U+200C and U+200D and rejects every other control or format character, including supplementary-plane ones (U+E0020 gets `INVALID_ARGUMENT`, test `:344`).
- **Gateway:** `tools.ts:28` removes the two joiners, then rejects `\p{Cc}\p{Cf}` with the `u` flag, which is the same set.
- **Direction markers stay forbidden for new names:** LRM/RLM, U+202A–E, U+2066–9 and U+061C are all format characters, so they're rejected (gateway test `:137`).
- **Joiner text is accepted on both sides:** ZWJ emoji and Persian ZWNJ pass on the gateway (`:138`) and on Android (`:342-343`).
- **Existing rows are never rewritten.**

## Nonblockers (no change requested)
1. **Look-alike names:** joiners are allowed anywhere, so "Li\u200Dve" looks like "Live" but has a different key. The original P3.7 look-alike concern therefore remains for these two characters only. That's the cost of the policy you chose.
2. **Legacy names can't be kept on edit:** a tag or category whose old name has a now-forbidden character can't be moved or saved while keeping that exact name. Native Save shows the validation message (`TagDefinitionDialog.kt:95-99`), and the AI gets `INVALID_ARGUMENT`. This matches "forbidden for NEW names", and no stored data changes.

## Evidence limitations
- **Native tests not complete:** the R1 regression test and the Android legacy/Unicode test (`AiTagIntegrationTest.kt:335-345`) are both in the native suite that's still running, so I don't claim either.
- **JVM run doesn't cover this logic:** no JVM unit test exercises `TagNames.validCharacters` or `storedKey`, since `TagPolicyTest` is unchanged.
- **Gateway parity:** this rests on `tagging-integration.test.mjs:135-140`.
- **Not tested, but same code path:**
  - AI `update_tag` and `rename_tag_category` scanning past a legacy row use the same `availableName` code as the creates that are tested.
  - The category ⋮ popup works the same way as the tag popup that is tested.
- **Out of scope:** data/backup closure.

I only used Read, Glob and Grep, and made no edits, commands or test runs.
