**VERDICT: HOLD** on frozen `4c92552ed`. The current source fixes the original triggers of both P2s. I'm holding because this change adds two reachable regressions, and each one brings back the kind of failure being closed. R1 lets a stale native edit silently overwrite a concurrent change. R2 reports a command that certainly did nothing as `OUTCOME_UNKNOWN`. Each fix is about one line, and with both fixed and tested I'd expect PASS WITH NONBLOCKERS. This is source review only. It does not approve production rollout, and it does not cover filesystem/backup handling.

## Original P2-1 (stale Manage form): original bug fixed; closure held on R1

- **Conflict carries the current definitions:** a stale save throws `TagDefinitionConflict(definitions())` inside the locked transaction (`TagStore.kt:134,140`; `TagModels.kt:23`).
- **Form reloads and keeps the draft:** `TagDefinitionDialog.kt:139-158` takes the current revision and categories and shows "Currently saved: name · category". It leaves the draft name and chosen category untouched and re-enables Save.
- **Second Save:** it sends the revision that was just displayed (`:105,113,120`). Any newer change, including an unseen one, fails `TagStore.kt:140` again, so the second Save can't clobber it.
- **Deleted target:** `targetMissing` disables Save and stops `save()` early (`:104,134,150`). Even if the revision matched, `check(update==1)` (`TagStore.kt:135,143`) prevents a silent re-create.
- **Removed category:** the spinner keeps it as "Removed category (id) — choose another" (`:179-183`). The foreign key is enforced (`TagStore.kt:20`), so Save fails with a visible message (`TagUi.kt:131`) instead of silently reassigning.
- **Rotation and process death:** the ViewModel is retained, and revision, current text, target-missing flag and categories are saved to the bundle (`:44-52,213-224`).
- **Live Manage:** it observes changes on start and stops on stop, with an 80 ms debounce (`TagManagerFragment.kt:100-111`). It allows one read at a time plus a "refresh again" flag (`:113-141`).

## Original P2-2 (false OUTCOME_UNKNOWN): original triggers fixed; closure held on R2

- **Checks run before any write:** they sit inside the receipt transaction (`AiTagExecutor.kt:93-121`). They cover case-insensitive duplicates across the whole tag or category namespace (`:80-92`), missing tags or categories, and moves to missing categories.
- **Rejections are exact:** `AiFailure` defaults to REJECTED (`AiProtocol.kt:203`) and is mapped at `AiCatalogExecutor.kt:115-116`, and the transaction rolls back.
- **Receipt capacity:** returns `RECEIPT_CAPACITY` / NOT_EXECUTED (`AiTagExecutor.kt:53-57`).
- **Truly ambiguous failures stay unknown:** failures at commit time or from storage still fall to `OUTCOME_UNKNOWN` (`AiCatalogExecutor.kt:117-118`).
- **Gateway:** the device always sends `data:{}` (`AiProtocol.kt:198,217`), which the gateway requires (`device.ts:222`). A REJECTED reply is cached and replayed (`device.ts:156-161,239-241`), and `get_command_result` reports it (`:153`).
- **No automatic widening:** rejections write no device receipt. Replays require the original scopes (`AiTagExecutor.kt:35`, `AiCatalogExecutor.kt:131,139`, `device.ts:152,159`), and nothing substitutes another tag or category.

## Regressions

### R1 (P2, narrow window): a Manage popup opens the form with old values but the new revision, silently undoing a concurrent rename/move
- **Where:** `TagManagerFragment.kt:165-167` (⋮ → Rename) and `:183-185` (Edit → Rename or move).
  - The popup is a plain AlertDialog that captures the `category`/`tag` from when the row was drawn.
  - When an item is picked, it passes the *current* `definitions` field, which the new live refresh replaces (`:102-103` → `:125`).
  - `TagForms.kt:21,30-32` then takes the name and category from the old object but the revision from the new `definitions.version`.
- **Repro:**
  1. Open Manage and tap Edit on "Live" (Genre).
  2. While the popup is open, the AI's `update_tag` renames it to "Concert" and moves it to Mood, and Manage refreshes.
  3. Tap "Rename or move". The form shows "Live" / Genre but carries the post-AI revision.
  4. Save, with or without edits. `TagStore.kt:140` passes and `:141-143` writes "Live" / Genre back.
- **Consequence:** no conflict screen appears, and the AI's committed change is silently lost. Category rename behaves the same way. This breaks the `staleNativeDefinitionEditCannotUndoAiMoveOrRename` contract. Before this change, Manage only refreshed after its own save or delete, so the values and the revision always came from the same snapshot.
- **Minimal fix:** in `addGroup`, keep the snapshot that drew the row (`val shown = definitions`) and pass `shown` to `TagForms.category`/`TagForms.tag` in both popup handlers. A concurrent change then hits the new conflict screen. Filter chips are rebuilt on every refresh, so they aren't affected.
- **Test:** open the row popup, rename that definition through the repository, wait for the refresh, choose Rename, Save. Assert the conflict message appears and the stored name is unchanged.

### R2 (P2, if legacy names exist): existing names with control/format characters make AI create/update return OUTCOME_UNKNOWN
- **Where:** `AiTagExecutor.kt:87,89` recompute the key of every *stored* name with `TagNames.key(it.name)`, outside the `try` at `:81-85`.
- **Why it throws:** `TagNames.clean` now rejects BMP control and format characters (`TagModels.kt:30`). A stored name like "👩‍💻 Code" (contains U+200D), Persian text with ZWNJ, or a soft hyphen throws `IllegalArgumentException`.
- **Consequence chain:**
  1. The exception hits the catch-all at `AiCatalogExecutor.kt:117-118` and becomes `OUTCOME_UNKNOWN` / `STORAGE_OR_EXECUTION_FAILURE`.
  2. The gateway caches that reply.
  3. `get_command_result` goes to the device (`device.ts:153`) and gets `RESULT_NOT_RETAINED` (`AiCatalogExecutor.kt:137`).
- **Affected commands:**
  - A legacy tag name breaks every `create_tag`, and every `update_tag` except one renaming that tag. The only exception is when a real duplicate is found earlier in the scan.
  - A legacy category name breaks `create_tag_category` and `rename_tag_category` the same way.
- **When it applies:** such names were accepted before this change (original P3.7). They survive an in-place upgrade, since there is no name migration (`TagStore.kt:36-39`). They also survive a tag database restore, which only checks structure and integrity (`TagBackupCoordinator.kt:299-316`).
- **Minimal fix:** compare stored names with `it.name.trim().lowercase(Locale.ROOT)`, which matches the `name_key` written at `TagStore.kt:249-252`.
- **Test:** insert a raw tag row whose name contains U+200D. Then AI `CreateTag("Other")` should be COMMITTED, and a duplicate of another existing name should return `TAG_NAME_EXISTS`.

## Extra fixes noted
- **Pagination:** no issue found. `AiTagExecutor.kt:251` sorts the included/excluded IDs and leaves page size and offset out of the revision. Duplicates are already rejected (`AiTagCommand.kt:89`). The query, catalog version and ordered result rows are still part of the revision, so real changes still return `TAG_RESULTS_CHANGED` (`AiTagIntegrationTest.kt:321-349`).
- **Unicode (nonblocker):** `TagModels.kt:30`, `AiTagCommand.kt:83` and `tools.ts:28` reject every format character, including the joiners U+200C and U+200D.
  - That blocks ZWJ emoji (👩‍💻, 🏳️‍🌈) and Persian or Indic text that needs joiners. This contradicts the adjudication's "ordinary international text and emoji remain allowed"; the gateway test only checks `Jazz 🎵`.
  - The device checks UTF-16 code units, so supplementary-plane format characters (such as the invisible tag characters U+E0020–E007F) pass in the native form, while the gateway rejects them. AI input is still protected by the gateway.
  - The owner should decide: either allow the joiners in all three layers or correct the adjudication text. If exact parity is wanted, the device check should iterate code points.

## Residual nonblocker (not a regression)
Tag assignments still raise the single catalog revision (`TagStore.kt:116,196,237`). While the AI keeps running `set_item_tags`, a native second Save can conflict again. Each conflict refreshes the shown values and revision, so the Save goes through at the first quiet gap. The original review's optional definition-only revision is still not implemented.

## Evidence limitations
- Source only: I did not run or confirm the 35-test native run or the 56-test gateway run.
- `TagFormLifecycleTest.kt:108-129`:
  - It opens the form from TagEditorFragment, not Manage.
  - The concurrent change is a rename, not an assignment.
  - It doesn't assert a second conflict after a third change, the deleted-target case, or the "Currently saved" text.
  - `scenario.recreate()` keeps the ViewModel, so the bundle restore path (`TagDefinitionDialog.kt:44-52`) is never exercised.
- `:131-147` covers the Manage refresh for create and rename only. It doesn't cover delete, re-subscribing after stop/start, or the popup path behind R1.
- `AiTagIntegrationTest.kt:271-319` covers:
  - duplicates across case and category,
  - missing IDs and categories,
  - unchanged definitions including the revision,
  - no receipt written,
  - receipt capacity.

  It has no legacy-name case (R2).
- The gateway rejection-replay test (`tagging-integration.test.mjs:119-133`) uses a synthetic device reply. The real device's `data:{}` shape is confirmed from source only.

I only used Read, Glob and Grep. I didn't write a plan file or call ExitPlanMode, because edits were excluded and those tools aren't available here.
