# Android reconnection: real process-death validation — 2026-10-21

Source: `ed4886a0d5abf64935e4e48b4ee9bed66d67542d`. Test APK built successfully with JDK21; APK hashes are in `checkpoint.json`.

## Executed result

**All five host-driven reconnection interruption scenarios passed** on the existing Windows `Pixel_9_API_35` emulator (`emulator-5576`, Android user 0), controlled from primary NotCodex through Windows ADB. No physical phone was used, no application data was cleared, and no other emulator/application was stopped.

Each scenario uses independent synthetic native stores. The test first creates a committed cloud baseline and real Room apply witness, makes a pending local edit, and retains catalog/recovery/replacement outboxes. During reconnection it fsyncs a marker at its selected durable boundary. The host records the PID and force-stops only `org.schabi.newpipe.debug.featurecloudsyncv120261019`. A fresh instrumentation process then resumes reconnection with the fake network peer offline.

| Interrupted boundary | Fresh-process result |
| --- | --- |
| Encrypted transition journal durable | PASS |
| Old pending outboxes archived | PASS |
| Room apply witness retired | PASS |
| Portable identity/baseline metadata moved | PASS |
| New connection persisted, journal still present | PASS |

Every fresh process verified that local catalog content, native IDs and installation incarnation survived; old pending files were retained in the private retired connection; the new service/token were selected; old baseline/witness state was removed; reconnection finished without network calls; and synchronization remained disabled after another store reopen. Individual preparation, interruption and verification logs plus killed PIDs are retained in each phase directory.

These are actual Android process termination/restart checks, separate from the previously executed 34 controller tests (including nine reconnection cases) in `../cloud-reconnect-runtime-20261021/`. They use a deterministic fake service and a fixture safety-backup callback. They do not claim physical power-loss durability, live HTTPS reconnection, or new end-to-end validation of the production safety-backup implementation. Existing native backup validation remains separately documented.
