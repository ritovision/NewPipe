import {
  generateAuthenticationOptions, generateRegistrationOptions,
  verifyAuthenticationResponse, verifyRegistrationResponse,
  type AuthenticationResponseJSON, type RegistrationResponseJSON,
} from '@simplewebauthn/server';
import { assert, b64, cleanLabel, constantTime, ControlError, digest, Env, randomToken, unb64 } from './shared';

// All entry points run inside ControlAccount.gate. One bounded durable record gives
// atomic state transitions without a new binding, schema migration or OAuth KV edits.
const KEY = 'owner-auth:v1';
const SESSION_MS = 3_600_000, CHALLENGE_MS = 300_000;
const MAX_STATE_BYTES = 1_048_576; // Well below the SQLite-backed DO row limit, including older runtimes.
const MAX_CREDENTIALS = 16, MAX_SESSIONS = 32, MAX_CHALLENGES = 64, MAX_CONSENTS = 64;
const MAX_ANONYMOUS_PENDING = 32; // Reserve at least half of each pool for verified owner work.
const TOKEN = /^[A-Za-z0-9_-]{43}$/;
const HASH = /^[0-9a-f]{64}$/;
type Action = 'register' | 'remove' | 'reset-sessions';
type Credential = {
  id: string; publicKey: string; counter: number; transports: string[];
  label: string; createdAt: number; lastUsedAt: number; backedUp: boolean;
  deviceType: 'singleDevice' | 'multiDevice';
};
export interface OwnerSession {
  hash: string; browserHash: string; csrf: string; epoch: number;
  credentialId?: string; recovery: boolean; createdAt: number; expiresAt: number;
  bootstrapEnrollment: boolean;
}
interface Binding { browserHash: string; sessionHash?: string; epoch: number; origin: string; owner: string }
interface Challenge extends Binding {
  id: string; challenge: string; purpose: 'login' | 'register' | 'stepup';
  expiresAt: number; action?: Action; target?: string;
  authorizer?: string; recoveryAuthorized?: boolean; bootstrapEnrollment?: boolean;
}
interface Permit extends Binding {
  hash: string; action: Action; target: string; authorizer?: string;
  recovery: boolean; expiresAt: number;
}
export interface Consent extends Binding {
  id: string; handle: string; proof: string; scopes: string[];
  clientName: string; clientDomain: string; redirectHost: string; redirectOrigin: string; redirectIsLoopback: boolean;
  expiresAt: number; status: 'pending' | 'claimed' | 'denied';
}
interface State {
  version: 1; epoch: number; credentials: Credential[]; sessions: OwnerSession[];
  challenges: Challenge[]; permits: Permit[]; consents: Consent[];
  audit: {event: string; at: number}[];
}
export interface OwnerAuthority { sessionHash: string; browserHash: string; csrf: string }
function text(value: unknown, maximum = 1024): string {
  assert(typeof value === 'string' && value.length <= maximum, 'INVALID_AUTH_REQUEST');
  return value;
}
function token(value: unknown): string {
  const result = text(value, 43); assert(TOKEN.test(result), 'INVALID_AUTH_REQUEST'); return result;
}
function hash(value: unknown): string {
  const result = text(value, 64); assert(HASH.test(result), 'INVALID_AUTH_REQUEST'); return result;
}
function action(value: unknown): Action {
  assert(value === 'register' || value === 'remove' || value === 'reset-sessions', 'INVALID_ACTION');
  return value;
}
function targetFor(operation: Action, value: unknown): string {
  if (operation === 'register') return cleanLabel(text(value, 80));
  if (operation === 'reset-sessions') { assert(value === '', 'INVALID_TARGET'); return ''; }
  return text(value, 1024);
}

export class OwnerAuth {
  constructor(private readonly storage: DurableObjectStorage, private readonly env: Env) {}

  private async load(): Promise<State> {
    const state = await this.storage.get<State>(KEY) ?? {
      version: 1, epoch: 1, credentials: [], sessions: [], challenges: [], permits: [], consents: [], audit: [],
    };
    assert(state.version === 1, 'AUTH_STATE_VERSION');
    const now = Date.now();
    state.sessions = state.sessions.filter(s => s.expiresAt > now && s.epoch === state.epoch &&
      (s.recovery || state.credentials.some(c => c.id === s.credentialId)));
    const live = (v: Binding & {expiresAt: number}) => v.expiresAt > now && v.epoch === state.epoch &&
      v.origin === this.env.PUBLIC_ORIGIN && v.owner === this.env.OWNER_ID &&
      (!v.sessionHash || state.sessions.some(s => s.hash === v.sessionHash));
    state.challenges = state.challenges.filter(live);
    state.permits = state.permits.filter(live);
    state.consents = state.consents.filter(live);
    return state;
  }
  private async save(state: State): Promise<void> {
    if (new TextEncoder().encode(JSON.stringify(state)).byteLength > MAX_STATE_BYTES) throw new ControlError('AUTH_STORAGE_LIMIT', 429);
    await this.storage.put(KEY, state);
    // Share the account alarm with pairing cleanup; never postpone an earlier alarm.
    const next = Date.now() + 360_000, alarm = await this.storage.getAlarm();
    if (alarm === null || alarm > next) await this.storage.setAlarm(next);
  }
  async prune(): Promise<boolean> {
    if (!await this.storage.get(KEY)) return false;
    const state = await this.load();
    await this.storage.put(KEY, state);
    return state.sessions.length + state.challenges.length + state.permits.length + state.consents.length > 0;
  }
  private audit(state: State, event: string): void {
    state.audit = [...state.audit, {event, at: Date.now()}].slice(-64);
  }
  private binding(state: State, browserHash: string, session?: OwnerSession): Binding {
    return {browserHash, sessionHash: session?.hash, epoch: state.epoch,
      origin: this.env.PUBLIC_ORIGIN, owner: this.env.OWNER_ID};
  }
  private session(state: State, input: Record<string, unknown>, csrf = true): OwnerSession {
    const found = state.sessions.find(s => s.hash === input.sessionHash && s.browserHash === input.browserHash);
    if (!found || (csrf && (typeof input.csrf !== 'string' || !constantTime(found.csrf, input.csrf)))) {
      throw new ControlError('NOT_AUTHORIZED', 401);
    }
    return found;
  }
  async validateAuthority(authority: unknown): Promise<void> {
    assert(authority && typeof authority === 'object' && !Array.isArray(authority), 'INVALID_AUTHORITY');
    this.session(await this.load(), authority as Record<string, unknown>);
  }
  private async newSession(state: State, browserHash: string, credentialId?: string): Promise<{token: string; session: OwnerSession}> {
    // A new authentication replaces only this browser's previous sessions. It is not
    // an implicit OAuth/device revocation or a global owner-session reset.
    state.sessions = state.sessions.filter(s => s.browserHash !== browserHash);
    // A successfully verified owner must still be able to recover at capacity.
    // Retire the oldest owner session rather than locking out authenticated recovery.
    if (state.sessions.length >= MAX_SESSIONS) {
      state.sessions.sort((a, b) => a.createdAt - b.createdAt);
      state.sessions.splice(0, state.sessions.length - MAX_SESSIONS + 1);
    }
    const raw = randomToken(), session: OwnerSession = {
      hash: await digest(raw), browserHash, csrf: randomToken(), epoch: state.epoch,
      credentialId, recovery: !credentialId, createdAt: Date.now(), expiresAt: Date.now() + SESSION_MS,
      bootstrapEnrollment: !credentialId,
    };
    state.sessions.push(session);
    // Bind a login-started consent at authentication commit, not only on a later
    // page load. Logout/revocation then invalidates it even before /continue.
    for (const pending of state.consents) {
      if (pending.browserHash === browserHash && !pending.sessionHash) pending.sessionHash = session.hash;
    }
    state.challenges = state.challenges.filter(c => c.browserHash !== browserHash || c.purpose !== 'login');
    this.audit(state, credentialId ? 'passkey-login' : 'recovery-login');
    return {token: raw, session};
  }
  private authorizeCredential(state: State, credentialId?: string): void {
    if (credentialId && !state.credentials.some(c => c.id === credentialId)) throw new ControlError('AUTHENTICATOR_REVOKED', 401);
  }
  private makePendingRoom<T extends Binding>(entries: T[], limit: number, authenticated: boolean, code: string): void {
    // Anonymous cookies are not identities. Anonymous traffic gets a separate
    // bounded FIFO budget and can evict only anonymous pending work, never an
    // authenticated step-up/registration/consent. A verified owner can reclaim
    // anonymous occupancy. Sustained traffic may still expire a pre-login flow;
    // that means restart sign-in, not carrying a recovery key to clear capacity.
    let anonymous = entries.filter(entry => !entry.sessionHash).length;
    while (entries.length >= limit || (!authenticated && anonymous >= MAX_ANONYMOUS_PENDING)) {
      const victim = entries.findIndex(entry => !entry.sessionHash);
      if (victim === -1) throw new ControlError(code, 429);
      entries.splice(victim, 1); anonymous -= 1;
    }
  }
  private async addChallenge(state: State, value: Omit<Challenge, 'id' | 'expiresAt'>, deadline = Date.now() + CHALLENGE_MS): Promise<Challenge> {
    if (state.challenges.filter(c => c.browserHash === value.browserHash).length >= 4) {
      throw new ControlError('CHALLENGE_LIMIT', 429);
    }
    this.makePendingRoom(state.challenges, MAX_CHALLENGES, !!value.sessionHash, 'CHALLENGE_LIMIT');
    if (deadline <= Date.now()) throw new ControlError('FRESH_VERIFICATION_REQUIRED', 401);
    const next = {...value, id: randomToken(), expiresAt: Math.min(deadline, Date.now() + CHALLENGE_MS)};
    state.challenges.push(next);
    await this.save(state);
    return next;
  }
  private async consumeChallenge(state: State, input: Record<string, unknown>, purpose: Challenge['purpose']): Promise<Challenge> {
    const id = token(input.challengeId);
    const found = state.challenges.find(c => c.id === id && c.purpose === purpose && c.browserHash === input.browserHash);
    if (!found) throw new ControlError('CHALLENGE_EXPIRED_OR_USED', 401);
    if (found.sessionHash) {
      const session = this.session(state, input);
      if (session.hash !== found.sessionHash) throw new ControlError('NOT_AUTHORIZED', 401);
    }
    this.authorizeCredential(state, found.authorizer);
    state.challenges = state.challenges.filter(c => c.id !== id);
    // Consume BEFORE cryptographic verification. Invalid/ambiguous responses cannot
    // reopen the challenge; later successful state changes are a second atomic put.
    await this.save(state);
    return found;
  }
  private credentialResponse(input: unknown): RegistrationResponseJSON | AuthenticationResponseJSON {
    assert(input && typeof input === 'object' && !Array.isArray(input), 'INVALID_CREDENTIAL');
    const value = input as RegistrationResponseJSON | AuthenticationResponseJSON;
    assert(typeof value.id === 'string' && value.id.length > 0 && value.id.length <= 1024 &&
      /^[A-Za-z0-9_-]+$/.test(value.id) && value.rawId === value.id && value.type === 'public-key', 'INVALID_CREDENTIAL');
    // The library verifies challenge, type, RP hash, origin, flags and signatures.
    // Also reject ALL iframe/cross-origin responses, including those missing topOrigin.
    const encoded = value.response?.clientDataJSON;
    assert(typeof encoded === 'string' && encoded.length <= 4096, 'INVALID_CLIENT_DATA');
    try {
      const data = JSON.parse(new TextDecoder('utf-8', {fatal: true, ignoreBOM: false}).decode(unb64(encoded, 4096)));
      assert((data.crossOrigin === undefined || data.crossOrigin === false) && data.topOrigin === undefined, 'INVALID_CLIENT_ORIGIN');
    } catch { throw new ControlError('INVALID_CLIENT_DATA', 400); }
    return value;
  }
  private rpID(): string {
    const url = new URL(this.env.PUBLIC_ORIGIN);
    assert(url.protocol === 'https:' && url.origin === this.env.PUBLIC_ORIGIN && !url.username && !url.password, 'INVALID_RP_ORIGIN');
    return url.hostname;
  }
  private async assertion(state: State, input: unknown, challenge: Challenge): Promise<Credential> {
    const data = this.credentialResponse(input) as AuthenticationResponseJSON;
    const credential = state.credentials.find(c => c.id === data.id);
    if (!credential) throw new ControlError('PASSKEY_NOT_RECOGNIZED', 401);
    const userHandle = data.response.userHandle;
    if (userHandle !== undefined && userHandle !== null && userHandle !== b64(new TextEncoder().encode(this.env.OWNER_ID))) {
      throw new ControlError('INVALID_USER_HANDLE', 401);
    }
    try {
      const result = await verifyAuthenticationResponse({
        response: data, expectedChallenge: challenge.challenge, expectedOrigin: this.env.PUBLIC_ORIGIN,
        expectedRPID: this.rpID(), requireUserVerification: true,
        credential: {id: credential.id, publicKey: Uint8Array.from(unb64(credential.publicKey, 8192)), counter: credential.counter},
      });
      if (!result.verified || !result.authenticationInfo.userVerified || result.authenticationInfo.credentialDeviceType !== credential.deviceType) {
        throw new Error('Invalid assertion');
      }
      credential.counter = result.authenticationInfo.newCounter;
      credential.backedUp = result.authenticationInfo.credentialBackedUp;
      credential.lastUsedAt = Date.now();
      return credential;
    } catch { throw new ControlError('PASSKEY_VERIFICATION_FAILED', 401); }
  }
  private async makePermit(state: State, session: OwnerSession, operation: Action, target: string, authorizer?: string): Promise<{permit: string}> {
    if (state.permits.length >= 32) throw new ControlError('ACTION_LIMIT', 429);
    const raw = randomToken();
    state.permits.push({...this.binding(state, session.browserHash, session), hash: await digest(raw),
      action: operation, target, authorizer, recovery: !authorizer, expiresAt: Date.now() + CHALLENGE_MS});
    await this.save(state);
    return {permit: raw};
  }
  private async consumePermit(state: State, session: OwnerSession, raw: unknown, operation: Action, target: string): Promise<Permit> {
    const key = await digest(token(raw));
    const permit = state.permits.find(p => p.hash === key && p.sessionHash === session.hash &&
      p.browserHash === session.browserHash && p.action === operation && p.target === target);
    if (!permit) throw new ControlError('FRESH_VERIFICATION_REQUIRED', 401);
    this.authorizeCredential(state, permit.authorizer);
    state.permits = state.permits.filter(p => p.hash !== key);
    await this.save(state);
    return permit;
  }

  async handle(path: string, input: Record<string, unknown>): Promise<unknown> {
    const state = await this.load();
    const browserHash = hash(input.browserHash);
    if (path === '/session') {
      try { return {session: this.session(state, input, false)}; }
      catch { return {session: null}; }
    }
    if (path === '/recover') {
      // This flag is supplied only by the trusted Worker after verifyOwner. No
      // public endpoint forwards arbitrary user fields to this internal route.
      assert(input.recoveryVerified === true, 'NOT_AUTHORIZED');
      const result = await this.newSession(state, browserHash);
      await this.save(state); return result;
    }
    if (path === '/login/cancel' || path === '/challenge/cancel') {
      const id = token(input.challengeId);
      const challenge = state.challenges.find(c => c.id === id && c.browserHash === browserHash);
      if (!challenge) return {cancelled: false, retryBootstrap: false};
      let retryBootstrap = false;
      if (path === '/login/cancel') {
        assert(challenge.purpose === 'login' && !challenge.sessionHash, 'INVALID_CHALLENGE');
      } else {
        const session = this.session(state, input);
        assert(challenge.sessionHash === session.hash, 'INVALID_CHALLENGE');
        if (challenge.bootstrapEnrollment && session.recovery && session.createdAt + CHALLENGE_MS > Date.now()) {
          session.bootstrapEnrollment = true; retryBootstrap = true;
        }
      }
      state.challenges = state.challenges.filter(c => c.id !== id);
      await this.save(state); return {cancelled: true, retryBootstrap};
    }
    if (path === '/logout') {
      const session = this.session(state, input);
      state.sessions = state.sessions.filter(s => s.hash !== session.hash);
      this.audit(state, 'logout'); await this.save(state); return {loggedOut: true};
    }
    if (path === '/credentials') {
      this.session(state, input, false);
      return {credentials: state.credentials.map(({id, label, createdAt, lastUsedAt, backedUp}) => ({id, label, createdAt, lastUsedAt, backedUp}))};
    }
    if (path === '/login/options' || path === '/stepup/options') {
      const session = path === '/stepup/options' ? this.session(state, input) : undefined;
      if (!state.credentials.length) throw new ControlError('NO_PASSKEYS', 409);
      const operation = session ? action(input.action) : undefined;
      const target = operation ? targetFor(operation, input.target) : undefined;
      // Login is discoverable; do not publish credential IDs to anonymous visitors.
      const options = await generateAuthenticationOptions({rpID: this.rpID(), timeout: CHALLENGE_MS,
        userVerification: 'required', ...(session ? {allowCredentials: state.credentials.map(c => ({id: c.id, transports: c.transports}))} : {})});
      const challenge = await this.addChallenge(state, {...this.binding(state, browserHash, session), challenge: options.challenge,
        purpose: session ? 'stepup' : 'login', action: operation, target});
      return {challengeId: challenge.id, options};
    }
    if (path === '/login/finish' || path === '/stepup/finish') {
      const purpose = path === '/login/finish' ? 'login' : 'stepup';
      const challenge = await this.consumeChallenge(state, input, purpose);
      const credential = await this.assertion(state, input.response, challenge);
      if (challenge.expiresAt <= Date.now()) throw new ControlError('CHALLENGE_EXPIRED_OR_USED', 401);
      if (purpose === 'login') {
        const result = await this.newSession(state, browserHash, credential.id);
        await this.save(state); return result;
      }
      const session = this.session(state, input);
      return this.makePermit(state, session, challenge.action!, challenge.target!, credential.id);
    }
    if (path === '/recovery-stepup') {
      const session = this.session(state, input);
      assert(input.recoveryVerified === true, 'NOT_AUTHORIZED');
      const operation = action(input.action);
      return this.makePermit(state, session, operation, targetFor(operation, input.target));
    }
    if (path === '/register/options') {
      const session = this.session(state, input), label = targetFor('register', input.label);
      if (state.credentials.length >= MAX_CREDENTIALS) throw new ControlError('PASSKEY_LIMIT', 409);
      let authorizer: string | undefined, recoveryAuthorized: boolean, deadline: number;
      let bootstrapEnrollment = false;
      if (session.recovery && session.bootstrapEnrollment && session.createdAt + CHALLENGE_MS > Date.now()) {
        session.bootstrapEnrollment = false; recoveryAuthorized = true; bootstrapEnrollment = true;
        deadline = session.createdAt + CHALLENGE_MS;
      } else {
        const permit = await this.consumePermit(state, session, input.permit, 'register', label);
        authorizer = permit.authorizer; recoveryAuthorized = permit.recovery; deadline = permit.expiresAt;
      }
      const options = await generateRegistrationOptions({
        rpName: 'NewPipe AI Control', rpID: this.rpID(), userName: 'NewPipe owner',
        userID: Uint8Array.from(new TextEncoder().encode(this.env.OWNER_ID)), timeout: CHALLENGE_MS,
        attestationType: 'none', authenticatorSelection: {residentKey: 'required', requireResidentKey: true, userVerification: 'required'},
        supportedAlgorithmIDs: [-7, -257], excludeCredentials: state.credentials.map(c => ({id: c.id, transports: c.transports})),
      });
      const challenge = await this.addChallenge(state, {...this.binding(state, browserHash, session),
        challenge: options.challenge, purpose: 'register', target: label, authorizer, recoveryAuthorized, bootstrapEnrollment}, deadline);
      return {challengeId: challenge.id, options};
    }
    if (path === '/register/finish') {
      const challenge = await this.consumeChallenge(state, input, 'register');
      this.session(state, input);
      if (state.credentials.length >= MAX_CREDENTIALS) throw new ControlError('PASSKEY_LIMIT', 409);
      const data = this.credentialResponse(input.response) as RegistrationResponseJSON;
      if (state.credentials.some(c => c.id === data.id)) throw new ControlError('PASSKEY_ALREADY_REGISTERED', 409);
      let result;
      try {
        result = await verifyRegistrationResponse({response: data, expectedChallenge: challenge.challenge,
          expectedOrigin: this.env.PUBLIC_ORIGIN, expectedRPID: this.rpID(), requireUserPresence: true,
          requireUserVerification: true, supportedAlgorithmIDs: [-7, -257]});
      } catch { throw new ControlError('PASSKEY_VERIFICATION_FAILED', 401); }
      if (challenge.expiresAt <= Date.now()) throw new ControlError('CHALLENGE_EXPIRED_OR_USED', 401);
      if (!result.verified || !result.registrationInfo.userVerified) throw new ControlError('PASSKEY_VERIFICATION_FAILED', 401);
      const info = result.registrationInfo;
      assert(info.credential.id === data.id && info.credential.publicKey.length <= 4096, 'INVALID_CREDENTIAL');
      const transports = (info.credential.transports ?? []).filter(t => ['usb','nfc','ble','internal','hybrid','smart-card'].includes(t)).slice(0,6);
      state.credentials.push({id: info.credential.id, publicKey: b64(info.credential.publicKey), counter: info.credential.counter,
        transports, label: challenge.target!, createdAt: Date.now(), lastUsedAt: 0,
        deviceType: info.credentialDeviceType, backedUp: info.credentialBackedUp});
      this.audit(state, 'passkey-added'); await this.save(state); return {registered: true};
    }
    if (path === '/credentials/remove') {
      const session = this.session(state, input), id = text(input.credentialId);
      if (!state.credentials.some(c => c.id === id)) throw new ControlError('PASSKEY_NOT_FOUND', 404);
      const permit = await this.consumePermit(state, session, input.permit, 'remove', id);
      if (state.credentials.length === 1 && (!permit.recovery || input.confirmLast !== true)) {
        throw new ControlError('LAST_PASSKEY_REQUIRES_RECOVERY', 409);
      }
      state.credentials = state.credentials.filter(c => c.id !== id);
      state.sessions = state.sessions.filter(s => s.credentialId !== id);
      state.challenges = state.challenges.filter(c => c.authorizer !== id);
      state.permits = state.permits.filter(p => p.authorizer !== id);
      this.audit(state, 'passkey-removed'); await this.save(state);
      return {removed: true, signedOut: session.credentialId === id};
    }
    if (path === '/sessions/reset') {
      const session = this.session(state, input);
      const permit = await this.consumePermit(state, session, input.permit, 'reset-sessions', '');
      if (!permit.recovery || input.confirmReset !== true) throw new ControlError('RECOVERY_CONFIRMATION_REQUIRED', 409);
      state.epoch += 1; assert(Number.isSafeInteger(state.epoch), 'AUTH_EPOCH_LIMIT');
      state.sessions = []; state.challenges = []; state.permits = []; state.consents = [];
      const result = await this.newSession(state, browserHash);
      this.audit(state, 'owner-sessions-reset'); await this.save(state); return result;
    }
    if (path === '/consent/begin') {
      if (state.consents.filter(c => c.browserHash === browserHash).length >= 8) {
        throw new ControlError('CONSENT_LIMIT', 429);
      }
      let session: OwnerSession | undefined;
      if (input.sessionHash) session = this.session(state, input, false);
      this.makePendingRoom(state.consents, MAX_CONSENTS, !!session, 'CONSENT_LIMIT');
      assert(Array.isArray(input.scopes) && input.scopes.length <= 32 && input.scopes.every(s => typeof s === 'string' && s.length <= 80), 'INVALID_SCOPE');
      const consent: Consent = {...this.binding(state, browserHash, session), id: randomToken(), proof: randomToken(),
        handle: text(input.handle, 2048), scopes: input.scopes as string[], clientName: cleanLabel(text(input.clientName, 1024)),
        clientDomain: text(input.clientDomain, 256), redirectHost: text(input.redirectHost, 256), redirectOrigin: text(input.redirectOrigin, 512), redirectIsLoopback: input.redirectIsLoopback === true,
        expiresAt: Date.now() + CHALLENGE_MS, status: 'pending'};
      state.consents.push(consent); await this.save(state); return consent;
    }
    if (path === '/consent/resume' || path === '/consent/claim' || path === '/consent/deny') {
      const consent = state.consents.find(c => c.id === input.flow && c.browserHash === browserHash && c.status === 'pending');
      if (!consent) throw new ControlError('CONSENT_EXPIRED_OR_USED', 401);
      if (path === '/consent/deny') {
        assert(constantTime(consent.proof, text(input.proof, 43)) && consent.handle === input.handle, 'INVALID_CONSENT');
        if (consent.sessionHash) {const session = this.session(state, input);assert(session.hash === consent.sessionHash, 'INVALID_CONSENT');}
        consent.status = 'denied'; await this.save(state); return consent;
      }
      const session = this.session(state, input, path === '/consent/claim');
      if (consent.sessionHash && consent.sessionHash !== session.hash) throw new ControlError('CONSENT_SESSION_CHANGED', 401);
      consent.sessionHash = session.hash;
      if (path === '/consent/claim') {
        assert(constantTime(consent.proof, text(input.proof, 43)) && consent.handle === input.handle, 'INVALID_CONSENT');
        assert(Array.isArray(input.scopes) && input.scopes.every(s => consent.scopes.includes(String(s))), 'INVALID_SCOPE');
        consent.status = 'claimed';
        this.audit(state, 'oauth-consent-committed');
      }
      await this.save(state); return consent;
    }
    throw new ControlError('NOT_FOUND', 404);
  }
}
