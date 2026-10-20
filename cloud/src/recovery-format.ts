/* SPDX-License-Identifier: GPL-3.0-or-later */
import { checkedHead } from "./protocol.ts";
import type { Head } from "./protocol.ts";
import { PAGE_LIMITS, byteLength, parsePage, sha256, validPageId } from "./pages.ts";
import type { CatalogPage } from "./pages.ts";

export class RecoveryError extends Error {
  readonly code: string;
  readonly status: number;
  constructor(code: string, status = 409) { super(code); this.name = "RecoveryError"; this.code = code; this.status = status; }
}
export function recoveryFail(code: string, status = 409): never { throw new RecoveryError(code, status); }
export const RECOVERY_LIMITS = Object.freeze({
  slots: 3, bytes: 256 * 1024 * 1024, totalBytes: 768 * 1024 * 1024,
  partBytes: 8 * 1024 * 1024, parts: 32, approvalMs: 10 * 60_000,
  pinMs: 10 * 60_000, partLeaseMs: 90_000, replacementCooldownMs: 60_000
});
export type RecoverySource = Readonly<{ head: Head; pages: number; bytes: number; manifest: string }>;
export type RecoveryManifest = Readonly<{ version: 1; kind: "device-zip" | "cloud-catalog";
  bytes: number; source: RecoverySource | null; native: Readonly<{ database: number; tags: number; uncompressedBytes: number }> | null; producerValidation: "native-restore-v1" | "catalog-pages-v1"; parts: readonly Readonly<{ bytes: number; sha256: string }>[] }>;
const hash = /^[0-9a-f]{64}$/;
export const archiveId = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export function recoveryObject(value: unknown, fields: readonly string[]): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) recoveryFail("INVALID_RECOVERY_REQUEST", 400);
  const o = value as Record<string, unknown>;
  if (Object.keys(o).length !== fields.length || !fields.every(k => Object.hasOwn(o, k))) recoveryFail("INVALID_RECOVERY_FIELDS", 400);
  return o;
}
function integer(value: unknown, min: number, max: number): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < min || value > max) recoveryFail("INVALID_RECOVERY_SIZE", 400);
  return value;
}
export function readRecoverySource(raw: unknown): RecoverySource {
  const o = recoveryObject(raw, ["head", "pages", "bytes", "manifest"]);
  const h = recoveryObject(o.head, ["serviceGeneration", "library", "epoch", "revision", "headCounter", "fingerprint"]);
  let head: Head;
  try { head = checkedHead(h as Head); } catch { return recoveryFail("INVALID_RECOVERY_HEAD", 400); }
  if (typeof o.manifest !== "string" || !hash.test(o.manifest)) recoveryFail("INVALID_RECOVERY_MANIFEST", 400);
  return Object.freeze({ head, pages: integer(o.pages, 1, PAGE_LIMITS.pages),
    bytes: integer(o.bytes, 1, PAGE_LIMITS.totalBytes), manifest: o.manifest });
}
export function readRecoveryManifest(raw: unknown, maximum = RECOVERY_LIMITS.bytes): RecoveryManifest {
  const o = recoveryObject(raw, ["version", "kind", "bytes", "source", "native", "producerValidation", "parts"]);
  if (o.producerValidation !== (o.kind === "device-zip" ? "native-restore-v1" : "catalog-pages-v1")) recoveryFail("RECOVERY_PRODUCER_VALIDATION_REQUIRED", 400);
  if (o.version !== 1 || (o.kind !== "device-zip" && o.kind !== "cloud-catalog")) recoveryFail("UNSUPPORTED_RECOVERY_FORMAT", 400);
  const bytes = integer(o.bytes, 22, Math.min(maximum, RECOVERY_LIMITS.bytes));
  if (!Array.isArray(o.parts) || o.parts.length !== Math.ceil(bytes / RECOVERY_LIMITS.partBytes) || o.parts.length > RECOVERY_LIMITS.parts) recoveryFail("INVALID_RECOVERY_PARTS", 400);
  let total = 0;
  const parts = o.parts.map((raw, index) => {
    const p = recoveryObject(raw, ["bytes", "sha256"]);
    const length = integer(p.bytes, 1, RECOVERY_LIMITS.partBytes);
    if (index < (o.parts as unknown[]).length - 1 && length !== RECOVERY_LIMITS.partBytes) recoveryFail("INVALID_RECOVERY_PART_SIZE", 400);
    if (typeof p.sha256 !== "string" || !hash.test(p.sha256)) recoveryFail("INVALID_RECOVERY_CHECKSUM", 400);
    total += length; return Object.freeze({ bytes: length, sha256: p.sha256 });
  });
  if (total !== bytes) recoveryFail("RECOVERY_SIZE_MISMATCH", 400);
  const source = o.kind === "cloud-catalog" ? readRecoverySource(o.source) : null;
  if (o.kind === "device-zip" && o.source !== null) recoveryFail("INVALID_DEVICE_BACKUP_SOURCE", 400);
  let native: RecoveryManifest["native"] = null;
  if (o.kind === "device-zip") {
    const n = recoveryObject(o.native, ["database", "tags", "uncompressedBytes"]);
    native = Object.freeze({ database: integer(n.database, 1, 2147483647), tags: integer(n.tags, 1, 2147483647),
      uncompressedBytes: integer(n.uncompressedBytes, 1, 1024 * 1024 * 1024) });
  } else if (o.native !== null) recoveryFail("INVALID_CLOUD_ARCHIVE_NATIVE_METADATA", 400);
  return Object.freeze({ version: 1, kind: o.kind, bytes, source, native, producerValidation: o.producerValidation as RecoveryManifest["producerValidation"], parts: Object.freeze(parts) });
}
export const headTuple = (h: Head) => [h.serviceGeneration, h.library, h.epoch, h.revision, h.headCounter, h.fingerprint];
export const sourceTuple = (s: RecoverySource) => [headTuple(s.head), s.pages, s.bytes, s.manifest];
export async function recoveryDigest(m: RecoveryManifest): Promise<string> {
  return sha256(JSON.stringify(["rv-recovery/1", m.kind, m.producerValidation, m.bytes, m.source ? sourceTuple(m.source) : null,
    m.native ? [m.native.database, m.native.tags, m.native.uncompressedBytes] : null, m.parts.map(p => [p.bytes, p.sha256])]));
}
export async function bytesDigest(bytes: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", bytes as BufferSource);
  return Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, "0")).join("");
}
export function cloudArchiveHeader(source: RecoverySource): string {
  return JSON.stringify(["rv-cloud-catalog/1", ...sourceTuple(source)]) + "\n";
}
export function cloudArchivePage(page: CatalogPage): string {
  return JSON.stringify([page.id, page.body]) + "\n";
}
export type CloudArchiveVerification = { header: boolean; carry: string; leaves: string[]; bytes: number };
const emptyCloud = (): CloudArchiveVerification => ({ header: false, carry: "", leaves: [], bytes: 0 });
function encodeBase64(bytes: Uint8Array): string {
  let s = ""; for (let i = 0; i < bytes.length; i += 8192) s += String.fromCharCode(...bytes.subarray(i, i + 8192));
  return btoa(s);
}
function decodeBase64(s: string): Uint8Array { return Uint8Array.from(atob(s), c => c.charCodeAt(0)); }
/** At most one 8 MiB part and one incomplete <=66 KiB line; no full-archive buffering/decompression. */
export async function verifyCloudArchivePart(previous: CloudArchiveVerification | null, input: Uint8Array,
  source: RecoverySource, last: boolean): Promise<CloudArchiveVerification> {
  const state = previous ? structuredClone(previous) : emptyCloud();
  if (state.carry.length > 100_000 || state.leaves.length > PAGE_LIMITS.pages) recoveryFail("RECOVERY_VERIFICATION_STATE_INVALID");
  const carry = decodeBase64(state.carry), bytes = new Uint8Array(carry.length + input.length);
  bytes.set(carry); bytes.set(input, carry.length);
  let start = 0;
  for (let i = 0; i < bytes.length; i++) {
    if (bytes[i] !== 10) continue;
    if (i - start > PAGE_LIMITS.pageBytes * 2 + 256) recoveryFail("INVALID_CLOUD_ARCHIVE_LINE", 422);
    let line: string, value: unknown;
    try { line = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(bytes.subarray(start, i)); value = JSON.parse(line); }
    catch { return recoveryFail("INVALID_CLOUD_ARCHIVE_ENCODING", 422); }
    if (JSON.stringify(value) !== line) recoveryFail("NONCANONICAL_CLOUD_ARCHIVE", 422);
    if (!state.header) {
      if (line + "\n" !== cloudArchiveHeader(source)) recoveryFail("CLOUD_ARCHIVE_SOURCE_MISMATCH", 422);
      state.header = true;
    } else {
      if (!Array.isArray(value) || value.length !== 2 || !validPageId(value[0]) || typeof value[1] !== "string") recoveryFail("INVALID_CLOUD_ARCHIVE_PAGE", 422);
      const page = { id: value[0], body: value[1] };
      if (state.leaves.length >= source.pages || (state.leaves.length && page.id <= state.leaves[state.leaves.length - 1]!.slice(0, 64))) recoveryFail("CLOUD_ARCHIVE_PAGE_ORDER", 422);
      try { parsePage(page); } catch { return recoveryFail("INVALID_CLOUD_ARCHIVE_PAGE", 422); }
      state.bytes += byteLength(page.body);
      if (state.bytes > source.bytes) recoveryFail("CLOUD_ARCHIVE_SIZE_MISMATCH", 422);
      state.leaves.push(page.id + ":" + await sha256(page.body));
    }
    start = i + 1;
  }
  if (bytes.length - start > PAGE_LIMITS.pageBytes * 2 + 256) recoveryFail("INVALID_CLOUD_ARCHIVE_LINE", 422);
  state.carry = encodeBase64(bytes.subarray(start));
  if (last && (!state.header || state.carry || state.leaves.length !== source.pages || state.bytes !== source.bytes ||
    await sha256("rv-cloud-pages/1\n" + state.leaves.join("\n")) !== source.manifest)) recoveryFail("CLOUD_ARCHIVE_MANIFEST_MISMATCH", 422);
  return state;
}
/** Transport/ZIP container checks only. Android must validate DB/schema/native restore before use. */
export function verifyZipStart(bytes: Uint8Array): void {
  if (bytes[0] !== 0x50 || bytes[1] !== 0x4b || bytes[2] !== 3 || bytes[3] !== 4) recoveryFail("INVALID_NATIVE_ZIP", 422);
}
export function verifyZipEnd(tail: Uint8Array, total: number): void {
  const view = new DataView(tail.buffer, tail.byteOffset, tail.byteLength);
  for (let i = tail.length - 22; i >= 0; i--) {
    if (view.getUint32(i, true) !== 0x06054b50) continue;
    const comment = view.getUint16(i + 20, true);
    if (i + 22 + comment !== tail.length) continue;
    const disk = view.getUint16(i + 4, true), directoryDisk = view.getUint16(i + 6, true);
    const onDisk = view.getUint16(i + 8, true), entries = view.getUint16(i + 10, true);
    const length = view.getUint32(i + 12, true), offset = view.getUint32(i + 16, true);
    if (disk !== 0 || directoryDisk !== 0 || entries === 0 || entries !== onDisk || entries === 65535 ||
      length === 0xffffffff || offset === 0xffffffff || offset + length !== total - tail.length + i) recoveryFail("UNSUPPORTED_NATIVE_ZIP", 422);
    return;
  }
  recoveryFail("TRUNCATED_NATIVE_ZIP", 422);
}
