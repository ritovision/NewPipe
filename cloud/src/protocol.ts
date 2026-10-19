/* SPDX-License-Identifier: GPL-3.0-or-later */
export const MAX_COUNTER = 9223372036854775807n;
export function counter(value: string): bigint {
  if (typeof value !== "string" || !/^(0|[1-9][0-9]{0,18})$/.test(value)) throw new Error("INVALID_COUNTER");
  const n = BigInt(value); if (n > MAX_COUNTER) throw new Error("INVALID_COUNTER"); return n;
}
export type Head = Readonly<{
  serviceGeneration: string; library: string; epoch: string;
  revision: string; headCounter: string; fingerprint: string;
}>;
export function checkedHead(h: Head): Head {
  for (const s of [h.serviceGeneration, h.library, h.epoch]) {
    if (typeof s !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(s)) throw new Error("INVALID_HEAD_IDENTITY");
  }
  counter(h.revision); counter(h.headCounter);
  if (!/^[0-9a-f]{64}$/.test(h.fingerprint)) throw new Error("INVALID_FINGERPRINT");
  return Object.freeze({ ...h });
}
export function sameHead(a: Head, b: Head): boolean {
  return a.serviceGeneration === b.serviceGeneration && a.library === b.library && a.epoch === b.epoch &&
    a.revision === b.revision && a.headCounter === b.headCounter && a.fingerprint === b.fingerprint;
}
export type Decision = "DISABLED" | "IDENTITY_REPAIR_REQUIRED" | "REENROLL_REQUIRED" | "ROLLBACK_SUSPECTED" |
  "RESOLVE_OPERATION" | "ENROLLMENT_REQUIRED" | "NOOP" | "ADOPT_BASELINE" | "PULL" | "PUSH" | "CONFLICT";
export type SyncInput = Readonly<{
  enabled: boolean; identityReady: boolean; unresolvedOperation: boolean;
  localFingerprint: string; baseline: Head | null; remote: Head; highestObserved: Head | null;
}>;
/** Pure decision only. No I/O, automatic conflict merging, or unreviewed data replacement. */
export function decide(s: SyncInput): Decision {
  if (!s.enabled) return "DISABLED";
  if (!s.identityReady) return "IDENTITY_REPAIR_REQUIRED";
  const remote = checkedHead(s.remote);
  if (!/^[0-9a-f]{64}$/.test(s.localFingerprint)) throw new Error("INVALID_FINGERPRINT");
  for (const known of [s.baseline, s.highestObserved]) {
    if (!known) continue;
    checkedHead(known);
    if (known.serviceGeneration !== remote.serviceGeneration || known.library !== remote.library) return "REENROLL_REQUIRED";
    const cmp = counter(remote.headCounter) - counter(known.headCounter);
    if (cmp < 0n || (cmp === 0n && !sameHead(known, remote)) ||
        (known.epoch === remote.epoch && counter(remote.revision) < counter(known.revision))) return "ROLLBACK_SUSPECTED";
  }
  if (s.unresolvedOperation) return "RESOLVE_OPERATION";
  if (!s.baseline) return "ENROLLMENT_REQUIRED";
  if (s.localFingerprint === remote.fingerprint) return sameHead(s.baseline, remote) ? "NOOP" : "ADOPT_BASELINE";
  const localChanged = s.localFingerprint !== s.baseline.fingerprint;
  const cloudChanged = !sameHead(s.baseline, remote);
  return localChanged ? (cloudChanged ? "CONFLICT" : "PUSH") : (cloudChanged ? "PULL" : "NOOP");
}
export type Preview = Readonly<{
  action: string; connectionGeneration: string; incarnation: string;
  localFingerprint: string; remote: Head; candidateDigest: string; expiresAt: string;
}>;
/** Caller computes actual local fingerprint inside the native writer barrier before using this. */
export function previewStillMatches(expected: Preview, actual: Preview, now: string): boolean {
  counter(expected.connectionGeneration); counter(actual.connectionGeneration);
  checkedHead(expected.remote); checkedHead(actual.remote);
  return counter(expected.expiresAt) > counter(now) && expected.action === actual.action &&
    expected.connectionGeneration === actual.connectionGeneration && expected.incarnation === actual.incarnation &&
    expected.localFingerprint === actual.localFingerprint && sameHead(expected.remote, actual.remote) &&
    expected.candidateDigest === actual.candidateDigest && expected.expiresAt === actual.expiresAt;
}
