**HOLD** — source-only review of `6a10bd5417f1fca95071d7cc3a8b12c6ac5d3bd9`.

1. **P1 — Library repair reverses the lock order and can deadlock.**  
   [LibraryRepository.kt:219](/home/rito/techstuff/ritoverse/newpipe-tag-review-data/app/src/main/java/org/schabi/newpipe/custom/library/LibraryRepository.kt:219) holds the Library monitor while calling `rebindExisting`, which acquires TagRuntime at line 166. Conversely, initialization acquires TagRuntime before the Library monitor at lines 94–100.  
   **Interleaving:** with Library unbound, repair holds the monitor; a concurrent tag/playlist operation acquires TagRuntime and waits for that monitor; repair then waits for TagRuntime. Both remain blocked.  
   **Correction:** acquire TagRuntime before the synchronized still-unbound check and retain that check through rebinding.

2. **P1 — Import validation accepts executable triggers hidden by its name filter.**  
   [TagBackupCoordinator.kt:330](/home/rito/techstuff/ritoverse/newpipe-tag-review-data/app/src/main/java/org/schabi/newpipe/custom/tagging/TagBackupCoordinator.kt:330) excludes `LIKE 'sqlite_%'`; `_` matches any character. A legal trigger named `sqliteXerase` therefore escapes the trigger rejection and schema comparison.  
   **Trigger:** import an otherwise valid TagStore v2 containing `sqliteXerase`, an after-insert trigger on `tag` that deletes `assignment`. Integrity checks pass, preparation accepts it, installation resets AI and replaces files, and subsequent tag creation deletes existing assignments.  
   **Correction:** reject triggers/views independently of name exclusions; match SQLite’s reserved prefix literally.

3. **P2 — Startup can use the interrupted swap’s binding after rollback restores another catalog.**  
   [LibraryRepository.kt:103](/home/rito/techstuff/ritoverse/newpipe-tag-review-data/app/src/main/java/org/schabi/newpipe/custom/library/LibraryRepository.kt:103) reads the binding before opening Room, where [CatalogOpenHelperFactory.kt:29](/home/rito/techstuff/ritoverse/newpipe-tag-review-data/app/src/main/java/org/schabi/newpipe/custom/tagging/CatalogOpenHelperFactory.kt:29) finally performs recovery.  
   **Trigger:** old/new catalogs have different Library IDs; terminate after installing the new binding but before `COMMITTED`; let Library initialization perform the first database open. It caches the new binding, restores the old files, then validates that cached binding against the old database. This produces a false repair requirement—or selects the wrong canonical playlist if that ID names another `Library`.  
   **Correction:** complete journal recovery before reading any installed binding or opening tag storage.

These are source-derived defects; I did not execute their reproductions. The reported R3 **307 JVM / 125 native** passes predate the latest changes. The frozen commit’s full rerun, scale, and host-driven process-death acceptance remain pending. The added death test explicitly recovers before reading and uses binding ID `1` on both sides, so it does not cover finding 3.