/* SPDX-License-Identifier: GPL-3.0-or-later */
import { readCatalog, readKey, keyId, fingerprint, CATALOG_LIMITS } from "./catalog.ts";
import type { Catalog, Key, Playlist, Category, Tag, Assignment } from "./catalog.ts";
import { planSequence } from "./sequence.ts";
import type { SequenceRow } from "./sequence.ts";

/** Packed D1 rows; editing one occurrence must not write one SQL row for every occurrence. */
export const PAGE_LIMITS = Object.freeze({ pageBytes: 32 * 1024, entries: 128, pages: 2_048,
  totalBytes: 32 * 1024 * 1024, totalEntries: 1_100_000, keyUnits: 16_600 });
export type Entry = readonly [string, unknown];
export type CatalogPage = Readonly<{ id: string; body: string }>;
export type PageChange = Readonly<{ id: string; body: string | null }>;
export type MediaHint = Readonly<{ key: Key; title: string; uploader: string; duration: string;
  streamType: string; thumbnail: string | null }>;
const encoder = new TextEncoder();
const rankPattern = /^[0-9a-f]{32}$/;
const pagePattern = /^[0-9a-f]{64}$/;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const compare = (a: string, b: string): number => a < b ? -1 : a > b ? 1 : 0;
export const byteLength = (s: string): number => encoder.encode(s).length;
function check(ok: unknown, reason: string): asserts ok { if (!ok) throw new Error(reason); }
export async function sha256(value: string): Promise<string> {
  const bytes = await crypto.subtle.digest("SHA-256", encoder.encode(value));
  return Array.from(new Uint8Array(bytes), b => b.toString(16).padStart(2, "0")).join("");
}
export function validPageId(id: unknown): id is string { return typeof id === "string" && pagePattern.test(id); }
export function checkedRank(value: unknown): string {
  check(typeof value === "string" && rankPattern.test(value) && value !== "0".repeat(32) && value !== "f".repeat(32), "INVALID_RANK"); return value;
}
const pair = (key: Key): readonly [number, string] => [key.service, key.url];
function tuple(value: unknown, size: number): unknown[] { check(Array.isArray(value) && value.length === size, "INVALID_ENTRY_VALUE"); return value; }
function key(value: unknown): Key { const p = tuple(value, 2); return readKey({ service: p[0], url: p[1] }); }
function text(value: unknown): string {
  check(typeof value === "string" && value.length <= CATALOG_LIMITS.textUnits, "INVALID_HINT_TEXT");
  // Reuse Unicode validation without applying URL normalization.
  readKey({ service: 0, url: value || "_" }); return value;
}
export function readHint(value: unknown): MediaHint {
  check(value !== null && typeof value === "object" && !Array.isArray(value), "INVALID_HINT");
  const h = value as Record<string, unknown>;
  const fields = ["key", "title", "uploader", "duration", "streamType", "thumbnail"];
  check(Object.keys(h).length === fields.length && fields.every(k => Object.hasOwn(h, k)), "INVALID_HINT");
  check(typeof h.duration === "string" && /^(0|[1-9][0-9]{0,18}|-1)$/.test(h.duration) && BigInt(h.duration) <= 9223372036854775807n, "INVALID_HINT_DURATION");
  check(typeof h.streamType === "string" && ["NONE", "VIDEO_STREAM", "AUDIO_STREAM", "LIVE_STREAM", "AUDIO_LIVE_STREAM", "POST_LIVE_STREAM", "POST_LIVE_AUDIO_STREAM"].includes(h.streamType), "INVALID_HINT_TYPE");
  return Object.freeze({ key: readKey(h.key), title: text(h.title), uploader: text(h.uploader), duration: h.duration,
    streamType: h.streamType, thumbnail: h.thumbnail === null ? null : text(h.thumbnail) });
}
export function parsePage(page: CatalogPage): Entry[] {
  check(validPageId(page.id) && typeof page.body === "string", "INVALID_PAGE");
  check(byteLength(page.body) <= PAGE_LIMITS.pageBytes, "PAGE_BYTE_LIMIT");
  let rows: unknown; try { rows = JSON.parse(page.body); } catch { throw new Error("INVALID_PAGE_JSON"); }
  check(Array.isArray(rows) && rows.length > 0 && rows.length <= PAGE_LIMITS.entries, "PAGE_ENTRY_LIMIT");
  let last: string | null = null;
  const entries = rows.map(raw => {
    const e = tuple(raw, 2);
    check(typeof e[0] === "string" && e[0].length > 0 && e[0].length <= PAGE_LIMITS.keyUnits, "INVALID_ENTRY_KEY");
    check(last === null || compare(last, e[0]) < 0, "PAGE_ORDER_OR_DUPLICATE");
    last = e[0]; return [e[0], e[1]] as const;
  });
  // One exact canonical JSON representation per immutable page. Reject alternate/trailing encodings.
  check(JSON.stringify(entries) === page.body, "NONCANONICAL_PAGE"); return entries;
}
export async function readPages(pages: readonly CatalogPage[]): Promise<{ catalog: Catalog; hints: readonly MediaHint[]; entries: readonly Entry[] }> {
  check(pages.length <= PAGE_LIMITS.pages, "CATALOG_PAGE_LIMIT");
  let bytes = 0, count = 0;
  const seen = new Set<string>(), entries: Entry[] = [];
  // Hash sequential pages without retaining extra digest buffers or unbounded Promise.all work.
  for (const page of pages) {
    check(!seen.has(page.id), "DUPLICATE_PAGE"); seen.add(page.id);
    bytes += byteLength(page.body); check(bytes <= PAGE_LIMITS.totalBytes, "CATALOG_BYTE_LIMIT");
    const rows = parsePage(page); count += rows.length; check(count <= PAGE_LIMITS.totalEntries, "CATALOG_ENTRY_LIMIT");
    check(page.id === await sha256(rows[0]![0]), "PAGE_ID_MISMATCH"); entries.push(...rows);
  }
  entries.sort((a, b) => compare(a[0], b[0]));
  for (let i = 1; i < entries.length; i++) check(entries[i - 1]![0] !== entries[i]![0], "DUPLICATE_CATALOG_ENTRY");
  const categories: Category[] = [], tags: Tag[] = [], playlists = new Map<string, Playlist & { rank: string }>();
  const sequences = new Map<string, Key[]>(), assignments = new Map<string, { key: Key; tags: string[] }>(), hints: MediaHint[] = [];
  let metadata = false;
  for (const [name, value] of entries) {
    if (name === "v") { check(!metadata && JSON.stringify(value) === "[1,1]", "INVALID_CATALOG_VERSION"); metadata = true; continue; }
    if (name.startsWith("c/")) {
      const v = tuple(value, 2); categories.push({ id: name.slice(2), name: v[0] as string, nameKey: v[1] as string });
    } else if (name.startsWith("t/")) {
      const v = tuple(value, 3); tags.push({ id: name.slice(2), name: v[0] as string, nameKey: v[1] as string, category: v[2] as string | null });
    } else if (name.startsWith("p/")) {
      const v = tuple(value, 3), id = name.slice(2);
      playlists.set(id, { id, name: v[0] as string | null, artwork: v[1] === null ? null : key(v[1]), rank: checkedRank(v[2]), items: [] });
    } else if (name.startsWith("s/")) {
      const parts = name.split("/"); check(parts.length === 3, "INVALID_SEQUENCE_KEY");
      const owner = parts[1]!; check(owner === "library" || owner === "unavailable" || uuidPattern.test(owner), "INVALID_SEQUENCE_OWNER");
      checkedRank(parts[2]); const list = sequences.get(owner) ?? []; list.push(key(value)); sequences.set(owner, list);
    } else if (name.startsWith("a/")) {
      const v = tuple(value, 3), k = key([v[0], v[1]]), tag = v[2];
      check(typeof tag === "string" && uuidPattern.test(tag) && name === `a/${keyId(k)}/${tag}`, "INVALID_ASSIGNMENT_KEY");
      const a = assignments.get(keyId(k)) ?? { key: k, tags: [] }; a.tags.push(tag); assignments.set(keyId(k), a);
    } else if (name.startsWith("h/")) {
      const h = readHint(value); check(name === `h/${keyId(h.key)}`, "INVALID_HINT_KEY"); hints.push(h);
    } else throw new Error("UNKNOWN_CATALOG_ENTRY");
  }
  check(metadata, "MISSING_CATALOG_VERSION");
  for (const owner of sequences.keys()) check(owner === "library" || owner === "unavailable" || playlists.has(owner), "ORPHAN_SEQUENCE");
  const ordered = [...playlists.values()].sort((a, b) => compare(a.rank, b.rank));
  check(new Set(ordered.map(p => p.rank)).size === ordered.length, "DUPLICATE_PLAYLIST_RANK");
  const catalog = readCatalog({ version: 1, serviceMap: 1, library: sequences.get("library") ?? [], unavailable: sequences.get("unavailable") ?? [],
    playlists: ordered.map(({ rank: _rank, ...p }) => ({ ...p, items: sequences.get(p.id) ?? [] })), categories, tags,
    assignments: [...assignments.values()] });
  const hintable = new Set(catalog.library.map(keyId)); catalog.playlists.forEach(p => { if (p.artwork) hintable.add(keyId(p.artwork)); });
  check(hints.every(h => hintable.has(keyId(h.key))), "ORPHAN_MEDIA_HINT");
  return { catalog, hints: Object.freeze(hints), entries };
}

/** Incremental deterministic packing keeps unaffected previous page ranges instead of shifting all boundaries. */
export async function packEntries(input: readonly Entry[], previous: readonly CatalogPage[] = []): Promise<readonly CatalogPage[]> {
  const entries = [...input].sort((a, b) => compare(a[0], b[0]));
  check(entries.length <= PAGE_LIMITS.totalEntries, "CATALOG_ENTRY_LIMIT");
  for (let i = 1; i < entries.length; i++) check(entries[i - 1]![0] !== entries[i]![0], "DUPLICATE_CATALOG_ENTRY");
  const parsed = previous.map(p => ({ rows: parsePage(p), bytes: byteLength(p.body) }))
    .sort((a, b) => compare(a.rows[0]![0], b.rows[0]![0]));
  // Tiny spill pages are not permanent boundaries: coalesce them with the preceding
  // range so repeated interior inserts cannot create one almost-empty page per edit.
  const cuts = parsed.filter((p, i) => i === 0 || p.rows.length >= PAGE_LIMITS.entries / 2 || p.bytes >= PAGE_LIMITS.pageBytes / 2)
    .map(p => p.rows[0]![0]);
  const pages: CatalogPage[] = []; let segment: Entry[] = [], size = 2, cut = 0, bytes = 0;
  const flush = async (): Promise<void> => {
    if (!segment.length) return;
    const body = JSON.stringify(segment); bytes += byteLength(body);
    check(bytes <= PAGE_LIMITS.totalBytes && pages.length < PAGE_LIMITS.pages, "CATALOG_STORAGE_LIMIT");
    pages.push(Object.freeze({ id: await sha256(segment[0]![0]), body })); segment = []; size = 2;
  };
  for (const entry of entries) {
    while (cut < cuts.length && compare(entry[0], cuts[cut]!) >= 0) { await flush(); cut++; }
    const entryBytes = byteLength(JSON.stringify(entry)); check(entryBytes + 2 <= PAGE_LIMITS.pageBytes, "ENTRY_BYTE_LIMIT");
    if (segment.length >= PAGE_LIMITS.entries || size + entryBytes + Number(segment.length > 0) > PAGE_LIMITS.pageBytes) await flush();
    size += entryBytes + Number(segment.length > 0); segment.push(entry);
  }
  await flush(); return Object.freeze(pages.sort((a, b) => compare(a.id, b.id)));
}

/** Client-side/reference compiler. No SQL, destructive merge or server trust in claimed fingerprints. */
export async function encodePages(input: Catalog, previous: readonly CatalogPage[] = [], mediaHints: readonly MediaHint[] = []): Promise<readonly CatalogPage[]> {
  const c = readCatalog(input), old = previous.length ? (await readPages(previous)).entries : [];
  const bySequence = new Map<string, SequenceRow[]>(), ranks = new Map<string, string>();
  for (const [name, value] of old) {
    if (name.startsWith("s/")) {
      const parts = name.split("/"), owner = parts[1]!, list = bySequence.get(owner) ?? [];
      list.push({ rank: parts[2]!, key: key(value) }); bySequence.set(owner, list);
    } else if (name.startsWith("p/")) ranks.set(name.slice(2), (value as unknown[])[2] as string);
  }
  const entries: Entry[] = [["v", [1, 1]]];
  const sequence = (owner: string, keys: readonly Key[]): void => {
    const plan = planSequence(bySequence.get(owner) ?? [], keys);
    for (const row of plan.rows) entries.push([`s/${owner}/${row.rank}`, pair(row.key)]);
  };
  sequence("library", c.library); sequence("unavailable", c.unavailable);
  const priorOrder = [...ranks].sort((a, b) => compare(a[1], b[1])).map(([id, rank]) => ({ rank, key: { service: 0, url: id } }));
  const order = planSequence(priorOrder, c.playlists.map(p => ({ service: 0, url: p.id }))).rows;
  c.playlists.forEach((p, i) => {
    entries.push([`p/${p.id}`, [p.name, p.artwork === null ? null : pair(p.artwork), order[i]!.rank]]); sequence(p.id, p.items);
  });
  c.categories.forEach(d => entries.push([`c/${d.id}`, [d.name, d.nameKey]]));
  c.tags.forEach(d => entries.push([`t/${d.id}`, [d.name, d.nameKey, d.category]]));
  c.assignments.forEach(a => a.tags.forEach(t => entries.push([`a/${keyId(a.key)}/${t}`, [...pair(a.key), t]])));
  mediaHints.map(readHint).forEach(h => entries.push([`h/${keyId(h.key)}`, h]));
  const result = await packEntries(entries, previous);
  // Hints and packing must obey the same receiver checks, never make an unreadable client payload.
  await readPages(result); return result;
}
export function changedPages(before: readonly CatalogPage[], after: readonly CatalogPage[]): readonly PageChange[] {
  const a = new Map(before.map(p => [p.id, p.body])), b = new Map(after.map(p => [p.id, p.body]));
  return Object.freeze([...new Set([...a.keys(), ...b.keys()])].sort(compare)
    .filter(id => a.get(id) !== b.get(id)).map(id => Object.freeze({ id, body: b.get(id) ?? null })));
}
export async function pageManifest(pages: readonly CatalogPage[]): Promise<string> {
  const hashes: string[] = [];
  for (const p of [...pages].sort((a, b) => compare(a.id, b.id))) hashes.push(`${p.id}:${await sha256(p.body)}`);
  return sha256("rv-cloud-pages/1\n" + hashes.join("\n"));
}
export async function semanticPageFingerprint(pages: readonly CatalogPage[]): Promise<string> { return fingerprint((await readPages(pages)).catalog); }
