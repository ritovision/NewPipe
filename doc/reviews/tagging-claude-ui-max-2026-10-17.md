**VERDICT: HOLD** on frozen `a78207dd4`. I found no P0 or P1 issues, and the data-safety rules hold. Category delete keeps videos and playlists, results are never truncated or re-selected, and offline mutations are never replayed. Old grants and older devices are denied tag access, and the native UI works without AI opt-in. Two reachable P2 issues remain, one in native behaviour and one in the AI wire contract. Your coordinator treated P2 as blocking in R2, so I'm holding on the same bar. Both fixes are small.

---

## P2-1: Renaming or moving a tag from Manage gets stuck after any concurrent change *(proven in source)*

- **Where:**
  - The form freezes the catalog revision into its arguments at open time: `TagForms.kt:32`, `TagDefinitionDialog.kt:41`.
  - Every Save, including retries, uses that frozen value: `TagDefinitionDialog.kt:110,118,125`.
  - The global revision goes up on every tag assignment, not just on definition edits: `TagStore.kt:116` (also `:196,:237`). It is then checked at `TagStore.kt:134,140`.
  - The Manage sheet only refreshes when it is created, after a successful form save, or after a delete: `TagManagerFragment.kt:89,94-109`. It never listens to `TagRuntime.observeChanges()`.
  - Rename and Edit open from those stale definitions: `TagManagerFragment.kt:135,153,171`.
- **Trigger:** open Tags → Manage. While it is open, the AI runs `set_item_tags` (or any assignment or cleanup) and the revision goes up. Then tap ⋮ → Rename and Save.
  - You get "Tag definitions changed while this form was open…".
  - Saving again fails again.
  - Cancel and reopening from the same sheet also fails, because the sheet is still stale.
  - The kept draft can never be saved. The only way out is closing Manage (or rotating) and retyping.
- **Consequence:** no data is lost and nothing is overwritten. But native rename/move stops working whenever the AI is active. The message says "Review the current values" while the sheet is still showing old values.
- **Minimal fix:**
  1. Make TagManagerFragment refresh on `TagRuntime.observeChanges()`, the same way TagBrowserModel does.
  2. When a revision conflict happens, reload the current definitions inside the dialog. Show the current name/category, keep the user's draft, and take the fresh revision, so a second Save is a deliberate overwrite.
  3. Optional: give definitions their own revision so assignment changes don't block renames.
- **Regression test:** add to `TagFormLifecycleTest`. Open the form from Manage, commit an unrelated assignment, and confirm the conflict appears. Then confirm the same dialog's second Save keeps the draft and succeeds, and that the sheet shows a concurrent rename or delete without reopening.

## P2-2: AI tag rejections that are certain come back as "outcome unknown", and the gateway caches that as final *(proven in source)*

- **Where:**
  - Tag commands are not checked before running and their failures are not mapped to clear codes: `AiTagExecutor.kt:124-153`.
  - Database errors come from the UNIQUE tag/category name (unique across all categories) at `TagStore.kt:24-25,120,128`, and the category foreign key at `:128,143`.
  - Missing-row and receipt-limit checks throw plain errors: `:135,143,170,220`.
  - All of these fall into the catch-all `OUTCOME_UNKNOWN/STORAGE_OR_EXECUTION_FAILURE`: `AiCatalogExecutor.kt:117-118`.
  - The gateway saves that reply (`device.ts:231-244`), replays it on retry (`:156-161`), and `get_command_result` then finds no device receipt (`:149-154`) and returns `RESULT_NOT_RETAINED`.
- **Triggers:**
  - `create_tag "Live"` under Mood when "Live" already exists under another category.
  - Creating a duplicate category, or renaming to an existing name.
  - `create_tag`/`update_tag` with a deleted or made-up `category_id`.
  - Rename, update or delete of an unknown ID while the revision is current.
  - More than 10,000 tag receipts within 24 h.
- **Consequence:** the tag transaction rolled back, so nothing changed. But the AI is told the outcome is unknown and can't find out more. The MCP instructions forbid retrying with a fresh ID, so it reports uncertainty or stalls, and never learns the actual cause (the name already exists).
- **Minimal fix:** before running, check that the target and category exist and that the name is free. Throw `AiFailure` with `TAG_NAME_EXISTS`, `CATEGORY_NOT_FOUND` or `TAG_NOT_FOUND` as REJECTED. Report receipt capacity as `RECEIPT_CAPACITY`/NOT_EXECUTED, as the Room path already does. Keep the catch-all only for failures at commit time.
- **Regression test:** in `AiTagIntegrationTest`, cover duplicates (including different case and different category), a deleted category, and a missing ID. Assert REJECTED with the right code, no receipt, and unchanged definitions. Add a gateway test that the cached reply stays REJECTED.

## P3 (non-blocking, proven in source unless noted)

1. **Raw error codes shown to users.** Failures from the native player path show the raw code, e.g. Add to queue with nothing playing shows `NO_ACTIVE_QUEUE` (`AiAndroidPlaybackPort.kt:69,94`, `AiProtocol.kt:203`, `TagBrowserModel.kt:47-49`). Fix: map codes to readable text in `perform`.
2. **Success messages get overwritten.** "Saved N videos to X" is replaced about 150 ms later, because saving changes the playlists table and triggers a refresh (`TagBrowserModel.kt:71-79` → `TagsActivity.kt:184-190`). Playing does the same via the history write to `streams`.
3. **Refreshes are not combined, and each one holds the global lock.**
   - Every invalidation or tag change starts a full Library read inside `access` while holding `TagRuntime.lock` (`TagBrowserModel.kt:69-103`, `TagRepository.kt:63-65,121`).
   - `dispose()` can't stop a read that has already started.
   - While TagsActivity is alive, even in the back stack, every AI tag write is followed by a full read. That slows bulk AI tagging and keeps the page on "Updating Library…" with its buttons disabled.
   - How bad this gets on a real device is unmeasured.
   - Fix: allow one read at a time plus a "needs refresh" flag, and stop observing while the activity is stopped.
4. **Changing page size breaks pagination.** The search revision includes `limit` and the order of the included/excluded lists (`AiTagExecutor.kt:197`), so changing `limit` between pages returns `TAG_RESULTS_CHANGED`. The tool description (`tools.ts:36`) doesn't warn about this.
5. **Back can silently cancel playback.** Pressing Back during "Working…" clears the ViewModel and cancels Play/Add before the player starts, with no feedback (`TagBrowserModel.kt:34,130`, `AiLocalTagPlayback.kt:16-19`).
6. **Plain dialogs are lost on rotation.** This covers the playlist-name dialog (`TagsActivity.kt:383-407`) and the Manage ⋮ menu and delete confirmation (`TagManagerFragment.kt:133-213`). The result of an in-flight delete is lost with it. Nothing unsafe happens, but typed input is lost.
7. **Invisible or right-to-left control characters are allowed in tag names.** Tag and category names accept them (`TagModels.kt:23-26`, `AiTagCommand.kt:83`, `tools.ts:28`), unlike client and device names (`AiRelayWire.kt:73`, `AiLocalStateStore.kt:52`). AI-supplied names could display misleadingly or look identical to existing ones.
8. **Smaller items:**
   - The Tags UI uses hardcoded English and "1 videos"-style plurals.
   - With flat tags only, Uncategorized starts collapsed (`TagsActivity.kt:213-214`).
   - In the editor, typing in Find during "Saving…" re-creates enabled checkboxes.

## How each workflow you listed came out

- **Native tag and rename:** an unsaved video is added to the Library before tags are written. A write that fails partway leaves the video saved but untagged, and Apply can be retried. Renaming is blocked by P2-1.
- **AND/OR/NOT/Untagged:** native and AI use the same filter, title/uploader search and sort. Untagged means no tags.
- **Save playlist / Play / Add while shuffled:** save keeps the exact captured order. Play replaces the queue using the background audio player. `appendInOrder` keeps order, the shuffle backup, and the item currently selected.
- **Rotating the editor or form during save:** the save task survives in its ViewModel, and the result is delivered once.
- **Remote category delete:** returns `removed_library_items: 0`. Revisions go up, so open editors refuse stale saves.
- **Old client and old device denial:** denied at three levels: the OAuth grant scopes, the device's hello, and the device's own permission ceiling.
- **Unsaved URL via AI:** needs `save_urls` then `get_item_tags` then `set_item_tags`, because `set_item_tags` only accepts saved videos. That is by design.
- **Previously saved PeerTube link:** native Play uses the local lookup, which works even for a removed instance. AI `play_items` is refused for hosts not in your instance list (by design). AI tagging doesn't check the host list. Refs with an explicit port are rejected by the gateway and the device. Different URL forms for the same video count as separate videos, which is how NewPipe already identifies streams.

## Evidence gaps (not proven bugs)

- I could not check how the OAuth library treats `requiredScopes`; it isn't in this worktree. Your prior doc says it is only advertised, not enforced.
- No test sends a 10,000-item native Play/Add against the 5 s and 2 s main-thread time limits.
- I couldn't confirm the 307 JVM, 54 gateway, 141 native and 3 host-kill results from source.
- Whether the nested AI tag command types parse correctly rests only on the androidTest JSON round trips.

This was a read-only review: no edits, commands, builds or device actions. I also couldn't write a plan file, because file writes were both excluded by your instructions and unavailable.
