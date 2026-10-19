# Sparse sequence planner — reviewed M1 contract

`src/sequence.ts` compiles a desired ordered sequence against an exact, validated base into deletions and insertions. It preserves repeated occurrences and service+URL identity. Positions are fixed-width lowercase 128-bit hexadecimal labels in the open interval (0, 2^128-1), stored as SQLite TEXT with binary lexical order, never floating-point values.

Duplicate matching is deterministic by prior occurrence order. A longest increasing subsequence of matched prior positions retains anchors; changed runs get labels between those anchors. This is not a general minimum-edit/LCS guarantee or a merge algorithm. A changed cloud/base revision invalidates the plan.

## Growth and bounded rebalance

Initial labels are centered around 2^127 with stride 2^64. At the head and tail, use this fixed stride, capped only by the available gap; **do not repeatedly bisect the remaining sentinel space**. Normal appends/prepends therefore do not wear out the tail/head after about 100 operations.

Interior insertion uses available spacing. If the gap is exhausted, redistribute a local window of at most **256 resulting occurrences**, retaining both external boundaries. Refill requires at least 2^32 spacing, avoiding a succession of nearly-empty local refills. The window expands through small bounded candidates, never across the entire library automatically. If no allowed window provides room, return `SEQUENCE_LOCAL_REBALANCE_REQUIRED` without producing a write plan or modifying input.

The caller must also apply `requireSequenceBudget` to actual deletes+inserts before staging. Many independent edits can legitimately cost more than one local window, so the overall row budget is a separate mandatory check; the 256-row limit is per redistribution, not a blanket total-plan guarantee. Counts include deletions and inserts; provider index/staging overhead needs its own measured admission budget.

## Applying a plan

Within **one atomic, expected-revision-fenced transaction**, apply every `remove` before any `put`. A vacated rank may be reused for a different key. Rank is only a sequence position, **not durable occurrence identity**. Performing puts first may conflict; applying without the exact base may delete a newer occurrence. Neither is permitted.

The maximum sequence length remains the 500,000 membership format ceiling. It is not a deployed Free-tier promise. Adversarial densely packed legacy labels may need an explicitly admitted migration/repack instead of an automatic huge rewrite.

## Tests and scope

Tests cover independent initial labels, malformed/sentinel ranks, duplicates, cross-service keys, input mutation, bounded interior refill, packed-gap refusal, rank reuse, 500 seeded round trips, and 100,000-entry duplicate/unique sequences. Regressions repeatedly append, prepend, and insert at the same interior position on 20,000-entry bases, asserting row-write counts and unchanged far-away labels.

A separate ephemeral workerd/D1 test checks actual TEXT ordering and atomic rollback. No Cloudflare resource is created. Generic sealed staging, production catalog publication and the full physical schema remain separate work; test SQL is not a deployable API.
