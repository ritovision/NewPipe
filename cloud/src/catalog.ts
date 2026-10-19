/* SPDX-License-Identifier: GPL-3.0-or-later */
/** Portable semantic model. No provider calls, native row IDs, or automatic merge. */
export const CATALOG_LIMITS = Object.freeze({
  streams: 50_000, memberships: 500_000, playlists: 2_000,
  tags: 20_000, categories: 2_000, textUnits: 8_192, bytes: 64 * 1024 * 1024,
});
export type Key = Readonly<{ service: number; url: string }>;
export type Category = Readonly<{ id: string; name: string; nameKey: string }>;
export type Tag = Category & Readonly<{ category: string | null }>;
export type Playlist = Readonly<{
  id: string; name: string | null; artwork: Key | null; items: readonly Key[];
}>;
export type Assignment = Readonly<{ key: Key; tags: readonly string[] }>;
export type Catalog = Readonly<{
  version: 1; serviceMap: 1; library: readonly Key[]; unavailable: readonly Key[];
  playlists: readonly Playlist[]; categories: readonly Category[];
  tags: readonly Tag[]; assignments: readonly Assignment[];
}>;
const encoder = new TextEncoder();
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
// Kotlin Char.isWhitespace = Java isWhitespace OR isSpaceChar. JS trim also
// strips BOM and misses Java's 001c..001f; do not use it for native name keys.
const edges = /^[\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+$/g;
export const trimNative = (s: string): string => s.replace(edges, "");
export const storedNameKey = (s: string): string => trimNative(s).toLowerCase();
const cmp = (a: string, b: string): number => a < b ? -1 : a > b ? 1 : 0;
export const keyId = (k: Key): string => JSON.stringify([k.service, k.url]);
const keyCmp = (a: Key, b: Key): number => a.service - b.service || cmp(a.url, b.url);
function requireThat(ok: unknown, label: string): asserts ok {
  if (!ok) throw new Error(`INVALID_CATALOG:${label}`);
}
function text(value: unknown): string {
  requireThat(typeof value === "string" && value.length <= CATALOG_LIMITS.textUnits, "text");
  for (let i = 0; i < value.length; i++) {
    const c = value.charCodeAt(i);
    if (c >= 0xd800 && c <= 0xdbff) {
      const next = value.charCodeAt(++i);
      requireThat(next >= 0xdc00 && next <= 0xdfff, "unpaired surrogate");
    } else requireThat(c < 0xdc00 || c > 0xdfff, "unpaired surrogate");
  }
  return value;
}
function object(value: unknown, fields: readonly string[]): Record<string, unknown> {
  requireThat(value !== null && typeof value === "object" && !Array.isArray(value), "object");
  const o = value as Record<string, unknown>;
  requireThat(Object.keys(o).length === fields.length && fields.every(k => Object.hasOwn(o, k)), "fields");
  return o;
}
function list(value: unknown, max: number): unknown[] {
  requireThat(Array.isArray(value) && value.length <= max, "collection limit");
  return value;
}
function id(value: unknown): string {
  const s = text(value); requireThat(uuid.test(s), "id"); return s;
}
function key(value: unknown): Key {
  const k = object(value, ["service", "url"]);
  requireThat(typeof k.service === "number" && Number.isInteger(k.service) && k.service >= 0 && k.service <= 2147483647, "service");
  const url = text(k.url); requireThat(trimNative(url).length > 0, "empty URL");
  return Object.freeze({ service: k.service, url });
}
function definition(value: unknown, tag: boolean): Category | Tag {
  const o = object(value, tag ? ["id", "name", "nameKey", "category"] : ["id", "name", "nameKey"]);
  const name = text(o.name), nameKey = text(o.nameKey);
  // Stored legacy definitions are readable even when new-entry validation would reject them.
  requireThat(nameKey === storedNameKey(name), "stored name key");
  const d = { id: id(o.id), name, nameKey };
  return Object.freeze(tag ? { ...d, category: o.category === null ? null : id(o.category) } : d);
}
function unique(values: readonly string[], label: string): Set<string> {
  const s = new Set(values); requireThat(s.size === values.length, label); return s;
}
/** Strict, bounded copy: caller mutation cannot alter a validated candidate. */
export function readCatalog(input: unknown): Catalog {
  const o = object(input, ["version", "serviceMap", "library", "unavailable", "playlists", "categories", "tags", "assignments"]);
  requireThat(o.version === 1 && o.serviceMap === 1, "unsupported codec/service map");
  const library = Object.freeze(list(o.library, CATALOG_LIMITS.streams).map(key));
  const unavailable = Object.freeze(list(o.unavailable, CATALOG_LIMITS.streams).map(key));
  const librarySet = unique(library.map(keyId), "duplicate Library key");
  unique(unavailable.map(keyId), "duplicate Unavailable key");
  requireThat(unavailable.every(k => librarySet.has(keyId(k))), "Unavailable outside Library");
  let memberships = library.length + unavailable.length;
  const playlists = Object.freeze(list(o.playlists, CATALOG_LIMITS.playlists).map(value => {
    const p = object(value, ["id", "name", "artwork", "items"]);
    const name = p.name === null ? null : text(p.name);
    requireThat(name === null || storedNameKey(name) !== "unavailable", "reserved Unavailable name");
    const items = Object.freeze(list(p.items, CATALOG_LIMITS.memberships).map(key));
    memberships += items.length;
    requireThat(memberships <= CATALOG_LIMITS.memberships, "membership limit");
    requireThat(items.every(k => librarySet.has(keyId(k))), "playlist item outside Library");
    // Permanent artwork is a reference, not a save operation; it may survive removal.
    return Object.freeze({ id: id(p.id), name, artwork: p.artwork === null ? null : key(p.artwork), items });
  }));
  unique(playlists.map(p => p.id), "duplicate playlist id");
  const categories = Object.freeze(list(o.categories, CATALOG_LIMITS.categories).map(x => definition(x, false) as Category));
  const tags = Object.freeze(list(o.tags, CATALOG_LIMITS.tags).map(x => definition(x, true) as Tag));
  const categoryIds = unique(categories.map(x => x.id), "duplicate category id");
  const tagIds = unique(tags.map(x => x.id), "duplicate tag id");
  unique(categories.map(x => x.nameKey), "duplicate category name");
  unique(tags.map(x => x.nameKey), "duplicate tag name");
  requireThat(tags.every(x => x.category === null || categoryIds.has(x.category)), "unknown category");
  let relations = 0;
  const assignments = Object.freeze(list(o.assignments, CATALOG_LIMITS.streams).map(value => {
    const a = object(value, ["key", "tags"]), k = key(a.key);
    requireThat(librarySet.has(keyId(k)), "orphan assignment is not portable");
    const assigned = list(a.tags, CATALOG_LIMITS.tags).map(id);
    unique(assigned, "duplicate assignment");
    requireThat(assigned.every(t => tagIds.has(t)), "unknown tag");
    relations += assigned.length; requireThat(relations <= CATALOG_LIMITS.memberships, "assignment limit");
    return Object.freeze({ key: k, tags: Object.freeze(assigned.sort(cmp)) });
  }));
  unique(assignments.map(a => keyId(a.key)), "duplicate assignment key");
  return Object.freeze({ version: 1, serviceMap: 1, library, unavailable, playlists, categories, tags, assignments });
}
/** Length-prefixed UTF-8 tokens avoid JSON escaping/key-order/platform differences. */
export function canonicalBytes(input: Catalog): Uint8Array {
  const c = readCatalog(input), parts = ["rv-cloud/1\n"];
  let size = 11;
  const token = (v: string | number): void => {
    const s = String(v), part = `${encoder.encode(s).length}:${s},`;
    size += encoder.encode(part).length;
    requireThat(size <= CATALOG_LIMITS.bytes, "encoded byte limit"); parts.push(part);
  };
  const writeKey = (k: Key): void => { token(k.service); token(k.url); };
  const keys = (items: readonly Key[]): void => { token(items.length); items.forEach(writeKey); };
  const nullable = (value: string | null): void => { token(value === null ? 0 : 1); if (value !== null) token(value); };
  token(c.version); token(c.serviceMap);
  token("library"); keys(c.library); token("unavailable"); keys(c.unavailable);
  token("playlists"); token(c.playlists.length);
  c.playlists.forEach(p => {
    token(p.id); nullable(p.name); token(p.artwork === null ? 0 : 1);
    if (p.artwork !== null) writeKey(p.artwork); keys(p.items);
  });
  token("categories"); token(c.categories.length);
  [...c.categories].sort((a, b) => cmp(a.id, b.id)).forEach(x => { token(x.id); token(x.name); token(x.nameKey); });
  token("tags"); token(c.tags.length);
  [...c.tags].sort((a, b) => cmp(a.id, b.id)).forEach(x => { token(x.id); token(x.name); token(x.nameKey); nullable(x.category); });
  const assignments = c.assignments.filter(a => a.tags.length > 0).sort((a, b) => keyCmp(a.key, b.key));
  token("assignments"); token(assignments.length);
  assignments.forEach(a => { writeKey(a.key); token(a.tags.length); a.tags.forEach(token); });
  const bytes = encoder.encode(parts.join(""));
  requireThat(bytes.length <= CATALOG_LIMITS.bytes, "encoded byte limit"); return bytes;
}
export async function fingerprint(c: Catalog): Promise<string> {
  const hash = await crypto.subtle.digest("SHA-256", canonicalBytes(c) as Uint8Array<ArrayBuffer>);
  return [...new Uint8Array(hash)].map(b => b.toString(16).padStart(2, "0")).join("");
}
const sameKeys = (a: readonly Key[], b: readonly Key[]): boolean => a.length === b.length && a.every((k, i) => keyId(k) === keyId(b[i]!));
function excess(a: readonly Key[], b: readonly Key[]): Key[] {
  const remaining = new Map<string, number>();
  b.forEach(k => remaining.set(keyId(k), (remaining.get(keyId(k)) ?? 0) + 1));
  return a.filter(k => {
    const id = keyId(k), count = remaining.get(id) ?? 0;
    if (count === 0) return true; remaining.set(id, count - 1); return false;
  });
}
export type DefinitionChanges = Readonly<{ added: string[]; removed: string[]; changed: string[] }>;
function definitions<T extends Category>(a: readonly T[], b: readonly T[]): DefinitionChanges {
  const before = new Map(a.map(x => [x.id, x])), after = new Map(b.map(x => [x.id, x]));
  return { added: [...after.keys()].filter(id => !before.has(id)).sort(cmp),
    removed: [...before.keys()].filter(id => !after.has(id)).sort(cmp),
    changed: [...after.keys()].filter(id => before.has(id) && JSON.stringify(before.get(id)) !== JSON.stringify(after.get(id))).sort(cmp) };
}
/** Exact membership/identity differences; UI paging/search is a later adapter, not truncation here. */
export function diffCatalog(beforeInput: Catalog, afterInput: Catalog) {
  const before = readCatalog(beforeInput), after = readCatalog(afterInput);
  const old = new Map(before.playlists.map(p => [p.id, p])), next = new Map(after.playlists.map(p => [p.id, p]));
  const playlists = [...new Set([...old.keys(), ...next.keys()])].sort(cmp).flatMap(id => {
    const a = old.get(id), b = next.get(id);
    if (a && b && a.name === b.name && JSON.stringify(a.artwork) === JSON.stringify(b.artwork) && sameKeys(a.items, b.items)) return [];
    return [{ id, kind: !a ? "added" : !b ? "removed" : "changed", before: a ?? null, after: b ?? null,
      added: excess(b?.items ?? [], a?.items ?? []), removed: excess(a?.items ?? [], b?.items ?? []),
      sequenceChanged: !sameKeys(a?.items ?? [], b?.items ?? []) }];
  });
  const oldAssignments = new Map(before.assignments.filter(a => a.tags.length > 0).map(a => [keyId(a.key), a]));
  const newAssignments = new Map(after.assignments.filter(a => a.tags.length > 0).map(a => [keyId(a.key), a]));
  const assignments = [...new Set([...oldAssignments.keys(), ...newAssignments.keys()])].sort((a, b) =>
    keyCmp((newAssignments.get(a) ?? oldAssignments.get(a))!.key, (newAssignments.get(b) ?? oldAssignments.get(b))!.key)).flatMap(id => {
    const a = oldAssignments.get(id), b = newAssignments.get(id), was = a?.tags ?? [], now = b?.tags ?? [];
    return JSON.stringify(was) === JSON.stringify(now) ? [] : [{ key: (b ?? a)!.key,
      added: now.filter(t => !was.includes(t)), removed: was.filter(t => !now.includes(t)) }];
  });
  return { added: excess(after.library, before.library), removed: excess(before.library, after.library),
    librarySequenceChanged: !sameKeys(before.library, after.library),
    unavailableAdded: excess(after.unavailable, before.unavailable), unavailableRemoved: excess(before.unavailable, after.unavailable),
    unavailableSequenceChanged: !sameKeys(before.unavailable, after.unavailable),
    playlistOrderChanged: JSON.stringify(before.playlists.map(p => p.id)) !== JSON.stringify(after.playlists.map(p => p.id)),
    playlists, categories: definitions(before.categories, after.categories), tags: definitions(before.tags, after.tags), assignments };
}

/** Strict shared media-key validator for typed delta/storage compilers. */
export { key as readKey };
