# Catalog service / native apply — adjudication

Date: 2026-10-19. Reviewed source c79263ae214fb14ff3d5d6eb2a2fd93957df8e47, Astra Extra High, one P1 and four P2, REQUEST_CHANGES. [Report copy](cloud-apply-service-astra-r1-2026-10-19.md), SHA-256 e96d51512fa6c206bea69328dbbb9bca0ebb0ddc7577dc699d7d55cded0e3cd9. No Claude was used.

All five findings are accepted as concrete implemented-path corrections before deployment:

1. **P1 opening order:** Always open the guarded Room helper before tags, even when cloud has never enrolled/no pending journal. Add a native swap interruption after new:ritovision-tags.db and prove restored old tags and subsequent writes survive reopen. Keep ordinary bare native fixtures supported without requiring a registered cloud context when no journal exists.
2. **P2 mismatched retirement identity:** Add a domain assertion to the same D1 retirement batch, preventing an existing staged digest from being retired under a different digest. The cleanup batch independently fences the staged identity too. Test existing upload mismatch and staged-start/retirement race; a preflight-only comparison is insufficient.
3. **P2 artwork-only streams:** Native orphan cleanup must protect any stream referenced as a permanent playlist thumbnail. Do not add artwork references to Library membership. Test cleanup after apply and between Room commit and recovery; unrelated orphan streams remain removable.
4. **P2 native relation budget:** Validate target active relations plus retained orphan relations before journaling, using overflow-safe arithmetic and the actual receiver bound. Apply the same constraint when decoding persisted plans. Pure boundary tests cover 500000 retained + 1 target without making a huge emulator fixture; native ordinary orphan tests prove the path remains admitted below bounds.
5. **P2 recovery escape:** Wrap blocked cloud-journal recovery in a distinct exception retaining its cause/code. An explicitly selected, already validated complete native backup may replace both stores despite that blocked journal. Stock backups that preserve current tags may not bypass it. Test corrupt-journal complete restore and stock refusal.

Also align native same-epoch rollback checks with the existing policy, and verify bidirectional transfer between two independent file-backed native catalogs with different integer IDs. The host-kill probe at BEFORE_TAG_COMMIT initially tried to read pending data already deleted inside that open transaction; its marker now uses the matching Room receipt, while explicitly asserting the tag transaction is still open. This corrects the probe rather than weakening recovery. The earlier before/after-Room host-kill probes passed; the corrected full matrix must be rerun.

The first candidate passed 189 backend,327JVM and251 native tests, but those do not override these findings. Final results and review approval are recorded only after actual revalidation. The default API remains disabled, no live migration/deployment has occurred at this adjudication, and production UI/R2/daily-budget/retention gates remain explicit.

The repository review copy normalizes trailing Markdown whitespace and line endings only. Raw original remains in the immutable review directory with the SHA-256 above; stored-copy SHA-256: 0b17ce66e1ebd7fdf5d88ba5419281a524bb32d68e99c52736dba5393dcc8876. No finding text was changed.
