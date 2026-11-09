import {startAuthentication, startRegistration} from '@simplewebauthn/browser';

const root = document.querySelector('[data-auth-root]');
if (root) {
  const status = root.querySelector('#auth-status');
  let busy = false;
  const message = value => {status.textContent = value;};
  const errors = {
    NO_PASSKEYS: 'No passkey is registered yet. Use First setup or emergency recovery below.',
    CHALLENGE_EXPIRED_OR_USED: 'This request expired or was already used. Try again.',
    NOT_AUTHORIZED: 'Your sign-in expired or the credential was not accepted. Reload and sign in again.',
    INVALID_CSRF: 'This page is no longer current. Reload it and try again.',
    LAST_PASSKEY_REQUIRES_RECOVERY: 'Removing the last passkey requires your emergency recovery key.',
    FRESH_VERIFICATION_REQUIRED: 'Verify with an existing passkey or recovery key before this change.',
    PASSKEY_VERIFICATION_FAILED: 'The passkey could not be verified. Try again or use emergency recovery.',
    PASSKEY_ALREADY_REGISTERED: 'That passkey is already registered. Choose a different authenticator.',
    CHALLENGE_LIMIT: 'There are too many pending attempts. Let them expire before retrying.',
  };
  async function post(path, data) {
    const response = await fetch('/auth' + path, {method: 'POST', credentials: 'same-origin',
      headers: {'Content-Type': 'application/json'}, body: JSON.stringify({csrf: root.dataset.csrf,
        browser_csrf: root.dataset.browserCsrf, ...data})});
    const value = await response.json();
    if (!response.ok) throw new Error(errors[value.error] ?? 'The operation was not completed. Reload and retry, or use emergency recovery.');
    return value;
  }
  async function ceremony(request, kind, login = false) {
    try {
      return kind === 'register' ? await startRegistration({optionsJSON: request.options})
        : await startAuthentication({optionsJSON: request.options});
    } catch (error) {
      // Cancel only the browser ceremony, not a submitted verification request.
      // A lost verification response may have committed; never reopen that challenge.
      try {
        const cancelled = await post(login ? '/login/cancel' : '/challenge/cancel', {challengeId: request.challengeId});
        if (cancelled.retryBootstrap) root.dataset.bootstrap = 'true';
      } catch { /* The original expiry remains the fail-closed fallback. */ }
      throw error;
    }
  }
  async function permit(action, target, bootstrap = false) {
    const recovery = root.querySelector('#recovery-key');
    if (recovery?.value) {
      const owner_key = recovery.value; recovery.value = '';
      return (await post('/recovery-stepup', {owner_key, action, target})).permit;
    }
    if (bootstrap && root.dataset.bootstrap === 'true') return undefined;
    const request = await post('/stepup/options', {action, target});
    const response = await ceremony(request, 'authenticate');
    return (await post('/stepup/finish', {challengeId: request.challengeId, response})).permit;
  }
  async function run(task) {
    if (busy) return;
    busy = true; root.querySelectorAll('button').forEach(button => button.disabled = true);
    try {message('Follow your device’s passkey or unlock prompt.'); await task();}
    catch (error) {
      message(error?.name === 'NotAllowedError' || error?.name === 'AbortError'
        ? 'The request was cancelled or timed out. Nothing was approved; you can try again.'
        : error?.message ?? 'The operation failed. Try again.');
    } finally {busy = false; root.querySelectorAll('button').forEach(button => button.disabled = false);}
  }
  root.querySelector('[data-login]')?.addEventListener('click', () => run(async () => {
    const request = await post('/login/options', {});
    const response = await ceremony(request, 'authenticate', true);
    await post('/login/finish', {challengeId: request.challengeId, response});
    location.assign(root.dataset.flow ? '/authorize/continue?flow=' + encodeURIComponent(root.dataset.flow) : '/setup');
  }));
  root.querySelector('[data-register]')?.addEventListener('click', () => run(async () => {
    const label = root.querySelector('#passkey-label').value.trim() || 'My passkey';
    const grant = await permit('register', label, true);
    const request = await post('/register/options', {label, permit: grant});
    root.dataset.bootstrap = 'false';
    const response = await ceremony(request, 'register');
    await post('/register/finish', {challengeId: request.challengeId, response}); location.assign('/setup');
  }));
  root.querySelectorAll('[data-remove]').forEach(button => button.addEventListener('click', () => run(async () => {
    if (!window.confirm('Remove this passkey and sign out owner sessions authenticated with it? Existing AI grants and paired devices are not removed.')) {message('Removal cancelled.'); return;}
    const last = button.dataset.last === 'true';
    if (last && !root.querySelector('#recovery-key')?.value) throw new Error(errors.LAST_PASSKEY_REQUIRES_RECOVERY);
    const grant = await permit('remove', button.dataset.remove);
    await post('/credentials/remove', {credentialId: button.dataset.remove, permit: grant, confirmLast: last}); location.assign('/setup');
  })));
  root.querySelector('[data-reset]')?.addEventListener('click', () => run(async () => {
    if (!root.querySelector('#recovery-key')?.value) throw new Error('Enter your emergency recovery key to revoke owner sessions.');
    if (!window.confirm('Revoke all owner browser sessions? ChatGPT grants and paired devices remain authorized until separately revoked.')) {message('Reset cancelled.'); return;}
    const grant = await permit('reset-sessions', '');
    await post('/sessions/reset', {permit: grant, confirmReset: true}); location.assign('/setup');
  }));
}
