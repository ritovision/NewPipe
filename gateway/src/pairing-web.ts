import {account, assert, ControlError, Env, esc} from './shared';
import {authority, browserContext, form, getSession, html, loginPanel, strictOrigin} from './owner-web';
import {type BrowserPair, comparisonCode} from './browser-pairing';

async function call(env: Env, operation: string, input: Record<string, unknown>): Promise<BrowserPair> {
  const response = await account(env).fetch(new Request('https://internal/browser-pair/' + operation,
    {method: 'POST', body: JSON.stringify(input)}));
  const value = await response.json() as BrowserPair & {error?: string};
  if (!response.ok) throw new ControlError(value.error ?? 'PAIRING_UNAVAILABLE', response.status);
  return value;
}
function id(value: unknown): string {
  assert(typeof value === 'string' && /^[0-9a-f]{64}$/.test(value), 'INVALID_PAIRING_REQUEST'); return value;
}
function outcome(pair: BrowserPair): string {
  if (pair.status === 'approved') return '<h1>Device approved</h1><p>Return to NewPipe to finish connecting. AI Control stays off until you choose Start AI Control in the app.</p>';
  if (pair.status === 'complete' || pair.status === 'committed') return '<h1>Enrollment accepted</h1><p>Return to NewPipe to check completion. This approval does not start AI Control or authorize ChatGPT by itself.</p>';
  return '<h1>Device not approved</h1><p>This setup was denied or cancelled. Your existing devices and Library have not been changed.</p>';
}
/** Owner approval remains browser-only: strict Origin, opaque session, transaction-specific
 * form proof, explicit comparison and an authoritative session recheck at device commit.
 * No pairing secret, credential or device private key is carried in this URL.
 */
export async function pairingPage(request: Request, env: Env): Promise<Response> {
  try {
    const url = new URL(request.url);
    if (request.method === 'GET') {
      const requestId = id(url.searchParams.get('request'));
      const headers = new Headers(), browser = await browserContext(request, env, headers);
      const session = await getSession(request, env);
      if (!session) return html('<h1>Connect your NewPipe device</h1>' + loginPanel(browser, '', requestId) +
        '<p>Sign in first. Nothing is paired until you compare the code and explicitly approve the device.</p>', headers);
      const ownerAuthority = {sessionHash: session.hash, browserHash: session.browserHash, csrf: session.csrf};
      const pair = await call(env, 'view', {request_id: requestId, ownerAuthority});
      if (pair.status !== 'pending') return html(outcome(pair), headers);
      return html(`<h1>Approve this NewPipe device?</h1><p>Continue only when you started <strong>Connect to AI</strong> in the NewPipe app on the device you are holding. Do not approve a link someone sent you.</p><h2>Compare the code</h2><p><strong style="font-size:2em;letter-spacing:.12em">${esc(comparisonCode(pair.id))}</strong></p><p>This exact code must be visible in NewPipe. You do not need to type or copy it.</p><p>Device: <strong>${esc(pair.name)}</strong><br>Identity: <code>${esc(pair.device_id)}</code><br>Gateway: <code>${esc(env.PUBLIC_ORIGIN)}</code></p><h2>Permissions selected in NewPipe</h2><ul>${pair.permissions.map(scope => '<li>' + esc(scope) + '</li>').join('') || '<li>No device actions selected</li>'}</ul><p>NewPipe still enforces its local permissions. Pairing does not authorize an AI client or start the background service.</p><form method="post" action="/pair/device"><input type="hidden" name="request_id" value="${esc(pair.id)}"><input type="hidden" name="proof" value="${esc(pair.proof)}"><input type="hidden" name="csrf" value="${esc(session.csrf)}"><label><input type="checkbox" name="comparison_confirmed" value="1" required> I started setup in NewPipe and its code matches.</label><button name="decision" value="allow">Approve this device</button> <button name="decision" value="deny" formnovalidate>Deny</button></form>`, headers);
    }
    if (request.method !== 'POST') throw new ControlError('METHOD_NOT_ALLOWED', 405);
    strictOrigin(request, env);
    const values = await form(request);
    const ownerAuthority = await authority(request, env, values.get('csrf'));
    const pair = await call(env, 'decide', {request_id: id(values.get('request_id')), ownerAuthority,
      proof: values.get('proof'), decision: values.get('decision'), comparison_confirmed: values.get('comparison_confirmed') === '1'});
    return html(outcome(pair));
  } catch (error) {
    const code = error instanceof ControlError ? error.code : 'PAIRING_UNAVAILABLE';
    return html(`<h1>Setup was not completed</h1><p>${esc(code)}</p><p>Return to NewPipe to check setup status. A lost response does not undo an approval; you can cancel an unfinished attempt or manage paired devices in the owner portal.</p>`, new Headers(), error instanceof ControlError ? error.status : 500);
  }
}
