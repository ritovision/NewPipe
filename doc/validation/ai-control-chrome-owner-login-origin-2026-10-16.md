# NewPipe AI Control — Chrome Android owner login `INVALID_ORIGIN` fix

**Date:** 2026-10-16
**Branch:** `feature/ai-control`
**Deployment:** `ritovision-newpipe-ai-control`, version `5c40531d-b530-4194-869f-381fed763154`

## Root cause

The setup and OAuth consent HTML helper returned `Referrer-Policy: no-referrer`.
For ordinary HTML form POSTs, that referrer policy causes browsers (including Chrome)
to send `Origin: null`, even when submitting a form to the page's own origin.
The Worker correctly refused `Origin: null` as untrusted, so legitimate Chrome
owner-key sign-ins were rejected with `INVALID_ORIGIN` *before key validation*.

See MDN documentation for the [effect of Referrer-Policy on Origin]
(https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Referrer-Policy#effect_on_the_origin_header).

## Fix

The common HTML response helper now returns `Referrer-Policy: same-origin`.
Same-origin form submissions retain their real `Origin`, while referrers remain
suppressed on cross-origin navigations. This applies to the setup and OAuth consent
pages without relaxing either the Worker's explicit `Origin` allowlist or its
strict same-origin check. Authentication secrets and session/token validation
are unchanged.

## Verification

- `npm test`: TypeScript check, Wrangler dry-run bundle and **31/31 gateway tests passed**.
- New regression test performs valid test-owner sign-in with canonical form `Origin`,
  checks session cookie and authenticated setup page, and verifies `Origin: null`
  or `https://evil.example` still receives HTTP 403.
- Deployed using existing authenticated Wrangler login. No Cloudflare secret
  values were read or printed during this fix.
- Live Worker GET `/setup`: HTTP 200 with `Referrer-Policy: same-origin`.
- Live Worker POST `/setup` with a deliberately invalid test key and correct
  `Origin`: HTTP 401 `NOT_AUTHORIZED` (passed origin check).
- Live Worker POST `/setup` with a deliberately invalid test key and `Origin: null`
  or an unrelated site: HTTP 403 `INVALID_ORIGIN`.
- Live Worker GET `/health`: HTTP 200.

**Still pending:** The owner must retry sign-in using a legitimate owner key in
Chrome to establish full live-browser success. This run deliberately never sent the
real key from an automation or copied it to logs, and did not pair the phone.
