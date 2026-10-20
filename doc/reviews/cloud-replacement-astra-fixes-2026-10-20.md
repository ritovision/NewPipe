**APPROVE_REPLACEMENT_BACKEND_FIX_CHECKPOINT — `6401e7907`**

Both reported P2 findings are resolved. No confirmed P1/P2 regression in this delta.

- **Recovery-record overwrite:** `RecoveryService.abandon()` (`cloud/src/recovery.ts:284`) now blocks next-sequence abandonment while the device has an ACTIVE replacement, inside the overwrite transaction. This preserves the required DONE record even under concurrent admission. Matching-sequence cleanup remains available at lines 275–277.
- **Cooldown timing:** `CatalogService.publishSealed()` (`cloud/src/service.ts:335–350`) checks cooldown and approval/upload expiry against SQLite execution time and records `last_replace_at` within the atomic publication batch. Delays before that batch no longer consume the cooldown. Preview/reserve clock changes and their SQL bindings are consistent.
- **Discovery:** `CatalogService.deviceState()` (`cloud/src/service.ts:158–161`) restricts the additional attempt ID/state to the authenticated library and device, returning only ACTIVE/STALE attempts.

Static review of the supplied inline delta only. The 17 focused passes are reported evidence; full backend regression remains pending. This verdict excludes Android, live deployment acceptance and operator tooling.