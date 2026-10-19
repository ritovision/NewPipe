# Semantic codec v1 — foundation contract

The transferable semantic catalog has exact fields `version`, `serviceMap`, `library`, `unavailable`, `playlists`, `categories`, `tags`, `assignments`. Version and service map are 1. It is not a full native ZIP or wire envelope; titles, uploader/cache metadata, cloud authority, native IDs and transport state do not belong here. A later transport envelope may carry validated display hints independently of the semantic fingerprint.

## Canonical bytes

UTF-8 header `rv-cloud/1\n`, followed by length-prefixed UTF-8 tokens: decimal byte length, colon, exact value, comma. No BOM; no Unicode or URL normalization. Integer tokens are canonical unsigned decimal. Nullable text emits presence token `0` or `1` then the text only when present. A media key emits numeric service token then exact saved URL token.

Token order:

1. Version, service map.
2. `library`, count, ordered media keys.
3. `unavailable`, count, ordered media keys.
4. `playlists`, count, playlists in portable display rank. Each: UUID, nullable exact name, artwork presence and optional media key, ordered occurrence count and keys. Duplicate ordinary occurrences are preserved.
5. `categories`, count, definitions sorted by UUID; each UUID, stored name, stored name key.
6. `tags`, count, definitions sorted by UUID; each UUID, stored name, stored name key, nullable category UUID.
7. `assignments`, count of **nonempty** assignment sets, ordered by numeric service and then UTF-16 code-unit URL order. Each key, tag count, sorted tag UUIDs. Missing and explicitly empty assignment sets are semantically identical.

SHA-256 of these exact bytes is the semantic fingerprint. Canonical UTF-16 string ordering is explicit for cross-runtime sorting and is not locale collation; token lengths are UTF-8 bytes. Reject unpaired surrogates rather than allowing platform-dependent replacement bytes. Runtime tests compare Python's independent encoder, JVM Kotlin, Android Kotlin and TypeScript expectations.

A permanent artwork reference may outlive saved membership, as native `refreshAutomaticThumbnail` only alters automatic selections. Selecting new artwork still follows native validity checks; transporting a reference must not silently save that stream. Full hint/native-row resolution is a later adapter gate.

## Bounds and immutable comparison

Current local model ceilings: 50,000 Library keys; 50,000 Unavailable keys; 2,000 playlists; 500,000 total Library/Unavailable/ordinary occurrences; 2,000 categories; 20,000 tags; 50,000 assignment keys and 500,000 assignment relations; 8,192 UTF-16 code units per text field; 64 MiB canonical bytes. The wire reader must enforce a smaller bounded request/chunk envelope **before JSON parsing**; these in-memory limits do not replace transport admission.

Reject unknown fields/version, duplicate Library/Unavailable keys, invalid references/IDs, ordinary reserved Unavailable names and duplicate definition names/identities/assignments. Portable assignment keys must be active Library keys. Native orphan metadata is handled by the future reviewed projection/apply adapter, not deleted by this codec.

Snapshots copy input collections. TypeScript additionally freezes its returned nested objects/arrays; Kotlin exposes read-only List types (not a security boundary against an unsafe cast). The durable preview/apply layer must own staged canonical bytes/digest, never rely only on a mutable DTO retained by a caller. This checkpoint has no persistent preview implementation.

## Native names

Stored-name keys use the fork's `TagNames.storedKey`: Kotlin trim semantics followed by Locale.ROOT lowercase. TypeScript explicitly matches native trim whitespace, which is not JavaScript's built-in trim set. Legacy controls, joiners, empty stored names and non-ASCII cases have explicit vectors; a new editable name still uses native `clean/key`, which is stricter.

These vectors test the listed supported cases, not every Unicode code point/version combination. A mismatched stored normalization is rejected explicitly; never silently rename/merge data to fit it. Full adapter/wire rollout must cover actual stored legacy keys and runtime Unicode-version compatibility before claiming arbitrary legacy-library enrollment.

## Decisions and preview checks

Counters are canonical decimal strings across JSON, checked against signed 64-bit maximum 9223372036854775807. SQL binds them as text and casts only after validation; D1 reads cast counters back to TEXT. JavaScript never converts them through Number.

Decision functions are pure: disabled/identity repair precede remote use; rollback/service changes block; unresolved operations resolve before rebasing; equal local and cloud semantic content can adopt a baseline; otherwise concurrent changes pause rather than merge.

Preview binding compares action, connection generation, database incarnation, local fingerprint, exact remote head, candidate digest and expiration. This does not itself take a native lock, check live state or grant authorization. The future coordinator must derive and verify those values under the documented barriers and preserve exact staged bytes.

## Scope of proof

The D1 control-plane proof does not publish a catalog or validate an HTTP token. It tests the internal already-authenticated admission context, repeated in-batch authorization predicates, sequence/receipt behavior, and rollback of injected domain mutations. Actual token derivation, domain compilation/digest calculation and resource budgets remain gated before exposing a service.
