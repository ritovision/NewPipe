/* SPDX-License-Identifier: GPL-3.0-or-later */
// Internal database shapes. Neither request JSON nor an Android credential supplies these grants.
export const REPLACEMENT_COOLDOWN_MS = 60_000;
export const REPLACE_GRANT = "EXISTS(SELECT 1 FROM cloud_replace_grants g WHERE g.library_id=d.library_id AND g.device_id=d.device_id AND g.service_generation=d.service_generation AND g.auth_generation=d.auth_generation AND g.can_replace=1)";
export type ReplacementApproval = {
  approval_id: string; header: string; next_epoch: string;
  retry_archive_id: string | null; retry_attempt_id: string | null; expires_at: number;
};
export type ReplacementAttempt = {
  attempt_id: string; header: string; next_epoch: string; recovery_sequence: string;
  archive_id: string; slot: number; retry_archive_id: string | null; expires_at: number;
  state: "ACTIVE" | "STALE" | "DONE" | "CANCELED";
};
export type ReplacementProof = {
  attempt: ReplacementAttempt; digest: string; objectEtag: string; objectVersion: string;
};
