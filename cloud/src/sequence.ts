/* SPDX-License-Identifier: GPL-3.0-or-later */
import { CATALOG_LIMITS, keyId, readKey } from "./catalog.ts";
import type { Key } from "./catalog.ts";

/** Fixed-width lexical labels: exact in SQLite TEXT, never floating-point positions. */
const END = (1n << 128n) - 1n;
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
 * Normal delete/insert/move preserves unaffected labels. On exhausted gaps, rebalance
 * only this bounded sequence; caller must admit the resulting row cost BEFORE staging.
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
  const labels: string[] = new Array(next.length);
  let start = 0, lower = 0n, rebalanced = false;
  for (let end = 0; end <= next.length; end++) {
    if (end < next.length && !preserved.has(end)) continue;
    const upper = end === next.length ? END : rank(before[matched[end]!]!.rank);
    const count = end - start;
    const step = (upper - lower) / BigInt(count + 1);
    if (count > 0 && step === 0n) { rebalanced = true; break; }
    for (let j = 0; j < count; j++) labels[start + j] = label(lower + step * BigInt(j + 1));
    if (end < next.length) labels[end] = label(upper);
    lower = upper; start = end + 1;
  }
  if (rebalanced) {
    const step = END / BigInt(next.length + 1);
    for (let i = 0; i < next.length; i++) labels[i] = label(step * BigInt(i + 1));
  }
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
