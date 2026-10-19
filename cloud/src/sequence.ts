/* SPDX-License-Identifier: GPL-3.0-or-later */
import { CATALOG_LIMITS, keyId, readKey } from "./catalog.ts";
import type { Key } from "./catalog.ts";

/** Fixed-width lexical labels: exact in SQLite TEXT, never floating-point positions. */
const END = (1n << 128n) - 1n;
const STRIDE = 1n << 64n;
const CENTER = 1n << 127n;
const MIN_REFILL = 1n << 32n;
export const MAX_LOCAL_REBALANCE = 256;
const pattern = /^[0-9a-f]{32}$/;
export type SequenceRow = Readonly<{ rank: string; key: Key }>;
export type SequencePlan = Readonly<{
  rows: readonly SequenceRow[]; remove: readonly string[]; put: readonly SequenceRow[];
  rebalanced: boolean;
}>;
function rank(value: string): bigint {
  if (typeof value !== "string" || !pattern.test(value)) throw new Error("INVALID_SEQUENCE_RANK");
  const n = BigInt("0x" + value);
  if (n === 0n || n === END) throw new Error("INVALID_SEQUENCE_RANK"); return n;
}
const label = (n: bigint): string => n.toString(16).padStart(32, "0");
function bounded(count: number): void {
  if (count > CATALOG_LIMITS.memberships) throw new Error("SEQUENCE_LIMIT");
}
/** Stable, deterministic duplicate-occurrence matching; no claim of globally minimal LCS. */
function anchors(matches: readonly number[]): Set<number> {
  const tails: number[] = [], indices: number[] = [], previous = new Int32Array(matches.length).fill(-1);
  for (let i = 0; i < matches.length; i++) {
    const value = matches[i]!; if (value < 0) continue;
    let lo = 0, hi = tails.length;
    while (lo < hi) { const mid = (lo + hi) >>> 1; if (tails[mid]! < value) lo = mid + 1; else hi = mid; }
    if (lo > 0) previous[i] = indices[lo - 1]!;
    tails[lo] = value; indices[lo] = i;
  }
  const keep = new Set<number>(); let i = indices[indices.length - 1] ?? -1;
  while (i >= 0) { keep.add(i); i = previous[i]!; }
  return keep;
}
/**
 * Compile an exact sequence against an already-validated, revision-fenced base.
 * Normal delete/insert/move preserves unaffected labels. Head/tail growth uses fixed
 * strides; an exhausted interior gap can redistribute only a bounded local window.
 * Caller admits actual remove+put cost BEFORE staging. No whole-sequence fallback.
 * The result is not an automatic merge or permission to mutate a newer base.
 */
export function planSequence(previous: readonly SequenceRow[], desired: readonly Key[]): SequencePlan {
  bounded(previous.length); bounded(desired.length);
  let last = 0n;
  const before = previous.map(row => {
    const n = rank(row.rank); if (n <= last) throw new Error("SEQUENCE_NOT_STRICTLY_ORDERED"); last = n;
    return Object.freeze({ rank: row.rank, key: readKey(row.key) });
  });
  const next = desired.map(readKey);
  const queues = new Map<string, { indices: number[]; used: number }>();
  before.forEach((row, i) => {
    const id = keyId(row.key); const q = queues.get(id) ?? { indices: [], used: 0 };
    q.indices.push(i); queues.set(id, q);
  });
  const matched = next.map(k => {
    const q = queues.get(keyId(k)); return q && q.used < q.indices.length ? q.indices[q.used++]! : -1;
  });
  const preserved = anchors(matched);
  const positions: (bigint | null)[] = next.map((_, i) => preserved.has(i) ? rank(before[matched[i]!]!.rank) : null);
  let rebalanced = false;
  // [start,end) is filled strictly between its unchanged external neighbors.
  const fill = (start: number, end: number, requireRoom: boolean): boolean => {
    const count = end - start; if (count === 0) return true;
    const lower = start === 0 ? 0n : positions[start - 1]!;
    const upper = end === next.length ? END : positions[end]!;
    if (lower === null || upper === null) throw new Error("SEQUENCE_INTERNAL_BOUNDARY");
    const availableStep = (upper - lower) / BigInt(count + 1);
    if (availableStep === 0n || (requireRoom && availableStep < MIN_REFILL)) return false;
    let step = availableStep, first = lower + step;
    if (start === 0 && end === next.length) {
      step = STRIDE; first = CENTER - BigInt(Math.floor(count / 2)) * STRIDE;
    } else if (start === 0) {
      step = availableStep < STRIDE ? availableStep : STRIDE;
      first = upper - step * BigInt(count);
    } else if (end === next.length) {
      step = availableStep < STRIDE ? availableStep : STRIDE;
      first = lower + step;
    }
    for (let j = 0; j < count; j++) positions[start + j] = first + step * BigInt(j);
    return true;
  };
  let scan = 0;
  while (scan < next.length) {
    if (positions[scan] !== null) { scan++; continue; }
    const start = scan; let end = start;
    while (end < next.length && positions[end] === null) end++;
    if (fill(start, end, false)) { scan = end; continue; }
    let fitted = false;
    for (const radius of [4, 8, 16, 32, 64, 127]) {
      let left = Math.max(0, start - radius), right = Math.min(next.length, end + radius);
      // A boundary must be a real retained/previously assigned position, not an unknown future gap.
      while (left > 0 && positions[left - 1] === null && right - left <= MAX_LOCAL_REBALANCE) left--;
      while (right < next.length && positions[right] === null && right - left <= MAX_LOCAL_REBALANCE) right++;
      if (right - left > MAX_LOCAL_REBALANCE) break;
      if (fill(left, right, true)) { rebalanced = true; scan = right; fitted = true; break; }
    }
    if (!fitted) throw new Error("SEQUENCE_LOCAL_REBALANCE_REQUIRED");
  }
  let checkPrevious = 0n;
  const labels = positions.map(value => {
    if (value === null || value <= checkPrevious || value >= END) throw new Error("SEQUENCE_INTERNAL_ORDER");
    checkPrevious = value; return label(value);
  });
  const rows = Object.freeze(next.map((key, i) => Object.freeze({ rank: labels[i]!, key })));
  const was = new Map(before.map(row => [row.rank, keyId(row.key)]));
  const now = new Map(rows.map(row => [row.rank, keyId(row.key)]));
  const remove = Object.freeze(before.filter(row => now.get(row.rank) !== keyId(row.key)).map(row => row.rank));
  const put = Object.freeze(rows.filter(row => was.get(row.rank) !== keyId(row.key)));
  return Object.freeze({ rows, remove, put, rebalanced });
}

/** Inspect normalized transfer cost before allowing any database mutations. */
export function requireSequenceBudget(plan: SequencePlan, rowBudget: number): void {
  if (!Number.isSafeInteger(rowBudget) || rowBudget < 0 || plan.remove.length + plan.put.length > rowBudget) {
    throw new Error("SEQUENCE_WRITE_BUDGET_EXCEEDED");
  }
}
