# Sparse sequence planner — local M1 proof

`src/sequence.ts` compiles a desired ordered sequence against an exact, validated base into deletions and insertions. It preserves repeated occurrences and service+URL identity. Positions are fixed-width lowercase 128-bit hexadecimal labels in the open interval (0, 2^128-1), stored as SQLite TEXT with binary lexical order, never floating-point values.

Duplicate matching is deterministic by prior occurrence order. A longest increasing subsequence of those matched old positions retains anchors; changed runs get evenly spaced labels between anchors. This is not a general minimum-edit/LCS guarantee for arbitrary duplicates, nor a merge policy. A changed cloud/base revision still invalidates the plan.

Normal deletion retains every surviving label. Insertion/move normally changes only the new/moved occurrence. If the label gap is exhausted, the bounded affected sequence is explicitly rebalanced and the actual deletion+insertion count is returned. `requireSequenceBudget` rejects a plan over the admitted row budget before any write. The maximum sequence length follows the 500,000 membership format ceiling; it is not a promise that a deployed Free-tier service can admit that write volume.

Tests include deterministic expected labels, malformed/sentinel ranks, duplicates, cross-service keys, input mutation, exhausted gaps, 500 seeded edit round trips, and 100,000-entry duplicate/unique sequences. A separate ephemeral workerd/D1 test checks actual TEXT ordering and transaction rollback after an insert conflict. No D1/R2 resource is created.

The planner is implemented. Generic sealed staging, revision/receipt integration with the production catalog tables, durable transfer budgets and the full physical catalog schema remain separate M1 work. The SQL in the test harness is not a deployment schema or externally exposed API.
