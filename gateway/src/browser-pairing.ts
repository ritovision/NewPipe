import {OwnerAuth, type OwnerAuthority} from './owner-state';
import {assert, cleanLabel, constantTime, ControlError, DEVICE_SCOPES, digest, type DeviceRegistration,
  type Env, randomToken, UUID, verifyDevice} from './shared';

const KEY = 'browser-pairing:v1', EPOCH = 'browser-pairing-epoch:v1';
const HASH = /^[0-9a-f]{64}$/, NONCE = /^[A-Za-z0-9_-]{43}$/;
const TTL = 300_000, RECOVERY_TTL = 86_400_000, MAX_RECORDS = 64, MAX_ANONYMOUS = 32;
export interface PairDetails {
  device_id: string; public_key: string; client_nonce: string; issued_at_ms: number;
  name: string; permissions: string[];
}
type Stage = 'pending' | 'approved' | 'denied' | 'cancelled' | 'committed' | 'complete';
export interface BrowserPair extends PairDetails {
  id: string; origin: string; owner: string; expiresAt: number; retainUntil: number; status: Stage;
  viewer?: OwnerAuthority; proof?: string; approver?: OwnerAuthority;
}
export type PairRegistration = DeviceRegistration & {pending?: boolean};
export interface FinishPreparation {pair: BrowserPair; registration: PairRegistration; done: boolean}

/** A versioned, unambiguous transcript. Names cannot contain control/format characters.
 * The request identifier is a hash of the signed begin transcript, NOT a bearer credential.
 * Status, cancellation and completion require this same device key and full immutable request.
 */
export function pairingMessage(operation: string, origin: string, value: PairDetails, id = ''): string {
  return ['newpipe-pairing-v1', operation, origin, value.device_id, value.public_key,
    value.client_nonce, String(value.issued_at_ms), value.name, value.permissions.join(','), id].join('\n');
}
function details(input: Record<string, unknown>): PairDetails {
  assert(input.pairing_version === 1, 'PAIRING_VERSION_UNSUPPORTED');
  assert(typeof input.device_id === 'string' && UUID.test(input.device_id));
  assert(typeof input.public_key === 'string' && /^[A-Za-z0-9_-]{80,256}$/.test(input.public_key));
  assert(typeof input.client_nonce === 'string' && NONCE.test(input.client_nonce));
  assert(Number.isSafeInteger(input.issued_at_ms) && (input.issued_at_ms as number) > 0);
  assert(typeof input.name === 'string' && input.name.length <= 80 && cleanLabel(input.name) === input.name);
  assert(Array.isArray(input.permissions) && input.permissions.length <= DEVICE_SCOPES.length);
  const permissions = input.permissions as unknown[];
  assert(permissions.every(p => typeof p === 'string' && DEVICE_SCOPES.includes(p as typeof DEVICE_SCOPES[number])));
  const sorted = [...new Set(permissions as string[])].sort();
  assert(JSON.stringify(sorted) === JSON.stringify(permissions), 'INVALID_PERMISSIONS');
  return {device_id: input.device_id, public_key: input.public_key, client_nonce: input.client_nonce,
    issued_at_ms: input.issued_at_ms as number, name: input.name, permissions: sorted};
}
function requestId(input: Record<string, unknown>): string {
  assert(typeof input.request_id === 'string' && HASH.test(input.request_id), 'INVALID_PAIRING_REQUEST');
  return input.request_id;
}
export function comparisonCode(id: string): string {return id.slice(0, 4).toUpperCase() + '-' + id.slice(4, 8).toUpperCase();}
export async function pairingResult(pair: BrowserPair): Promise<Record<string, unknown>> {
  return {pairing_version: 1, protocol_version: 1, request_id: pair.id, status: pair.status,
    paired: pair.status === 'complete', device_id: pair.device_id, owner_id: pair.owner,
    client_nonce: pair.client_nonce, public_key_hash: await digest(pair.public_key),
    name: pair.name, permissions: pair.permissions, issued_at_ms: pair.issued_at_ms,
    expires_at_ms: pair.expiresAt, comparison_code: comparisonCode(pair.id)};
}

/** Every method runs under ControlAccount.gate. Cross-object registration happens outside it. */
export class BrowserPairings {
  constructor(private readonly storage: DurableObjectStorage, private readonly env: Env) {}
  private async load(): Promise<BrowserPair[]> {
    return (await this.storage.get<BrowserPair[]>(KEY) ?? []).filter(p => p.retainUntil > Date.now() &&
      p.origin === this.env.PUBLIC_ORIGIN && p.owner === this.env.OWNER_ID);
  }
  private async save(pairs: BrowserPair[], nextEpoch?: string): Promise<void> {
    await this.storage.transaction(async tx => {
      await tx.put(KEY, pairs);
      if (nextEpoch) await tx.put(EPOCH, nextEpoch);
    });
    const next = Date.now() + 360_000, alarm = await this.storage.getAlarm();
    if (alarm === null || alarm > next) await this.storage.setAlarm(next);
  }
  private async epoch(): Promise<string> {
    let value = await this.storage.get<string>(EPOCH);
    if (!value) {value = randomToken(12); await this.storage.put(EPOCH, value);}
    return value;
  }
  async nonce(): Promise<Record<string, unknown>> {
    // Public admission generation + unpredictable suffix. This is not authentication.
    // Its full value is signed by the device and becomes part of the immutable request ID.
    return {pairing_version: 1, client_nonce: await this.epoch() + randomToken(20)};
  }
  private anonymous(pair: BrowserPair): boolean {
    return !pair.viewer && !pair.approver && (pair.status === 'pending' || pair.status === 'cancelled');
  }
  async prune(): Promise<boolean> {
    if (!await this.storage.get(KEY)) return false;
    const pairs = await this.load(); await this.storage.put(KEY, pairs); return pairs.length > 0;
  }
  private find(pairs: BrowserPair[], id: string): BrowserPair {
    const pair = pairs.find(p => p.id === id);
    if (!pair) throw new ControlError('PAIRING_EXPIRED_OR_UNKNOWN', 410);
    return pair;
  }
  private async prove(operation: string, input: Record<string, unknown>): Promise<{value: PairDetails; id: string}> {
    const value = details(input), id = await digest(pairingMessage('begin', this.env.PUBLIC_ORIGIN, value));
    if (operation !== 'begin' && requestId(input) !== id) throw new ControlError('PAIRING_REQUEST_MISMATCH', 401);
    if (typeof input.signature !== 'string' || !await verifyDevice(value.public_key,
      pairingMessage(operation, this.env.PUBLIC_ORIGIN, value, operation === 'begin' ? '' : id), input.signature)) {
      throw new ControlError('NOT_AUTHORIZED', 401);
    }
    return {value, id};
  }
  private async devices(): Promise<PairRegistration[]> {return await this.storage.get<PairRegistration[]>('devices') ?? [];}
  private checkDevice(devices: PairRegistration[], value: PairDetails): PairRegistration | undefined {
    const old = devices.find(d => d.deviceId === value.device_id);
    if (old?.revoked || (old && (old.publicKey !== value.public_key || old.ownerId !== this.env.OWNER_ID))) {
      throw new ControlError('PAIRING_REJECTED', 401);
    }
    return old;
  }
  async begin(input: Record<string, unknown>): Promise<Record<string, unknown>> {
    const {value, id} = await this.prove('begin', input), pairs = await this.load();
    this.checkDevice(await this.devices(), value);
    const old = pairs.find(p => p.id === id);
    if (old) return pairingResult(old); // Retries never renew the deadline or revive a terminal state.
    if (Math.abs(Date.now() - value.issued_at_ms) > 60_000) throw new ControlError('CLOCK_SKEW', 400);
    if (value.client_nonce.slice(0, 16) !== await this.epoch()) throw new ControlError('PAIRING_RESTART_REQUIRED', 409);
    let nextEpoch: string | undefined;
    // Anonymous keys are not owner identities. Reserve space for owner-viewed, approved,
    // and committed work; untrusted requests can evict only other anonymous requests.
    while (pairs.length >= MAX_RECORDS || pairs.filter(p => this.anonymous(p)).length >= MAX_ANONYMOUS) {
      const victim = pairs.findIndex(p => this.anonymous(p));
      if (victim < 0) throw new ControlError('PAIRING_LIMIT', 429);
      pairs.splice(victim, 1);
      // Evicted pending AND cancelled IDs may never be recreated by replaying their
      // original signed begin. Rotation is atomic with eviction; retained records
      // remain usable even though their nonce belongs to the previous generation.
      nextEpoch = randomToken(12);
    }
    const pair: BrowserPair = {...value, id, origin: this.env.PUBLIC_ORIGIN, owner: this.env.OWNER_ID,
      expiresAt: Date.now() + TTL, retainUntil: Date.now() + TTL, status: 'pending'};
    pairs.push(pair); await this.save(pairs, nextEpoch); return pairingResult(pair);
  }
  async status(input: Record<string, unknown>): Promise<Record<string, unknown>> {
    const {value, id} = await this.prove('status', input), pair = this.find(await this.load(), id);
    this.checkDevice(await this.devices(), value);
    return pairingResult(pair);
  }
  async cancel(input: Record<string, unknown>): Promise<Record<string, unknown>> {
    const {id} = await this.prove('cancel', input), pairs = await this.load(), pair = this.find(pairs, id);
    // Once committed, cancellation cannot undo the enrollment. Return the truthful status;
    // device revocation remains a separate owner action. The app may discard local setup.
    if (pair.status === 'pending' || pair.status === 'approved') {pair.status = 'cancelled'; await this.save(pairs);}
    return pairingResult(pair);
  }
  async view(input: Record<string, unknown>): Promise<BrowserPair> {
    const auth = new OwnerAuth(this.storage, this.env);
    await auth.validateAuthority(input.ownerAuthority);
    const pairs = await this.load(), pair = this.find(pairs, requestId(input));
    if (pair.status !== 'pending') return pair;
    if (pair.viewer) {
      // A logout, credential removal or new login cannot resurrect a previously viewed approval.
      await auth.validateAuthority(pair.viewer);
      const current = input.ownerAuthority as OwnerAuthority;
      if (current.sessionHash !== pair.viewer.sessionHash || current.browserHash !== pair.viewer.browserHash) {
        throw new ControlError('PAIRING_RESTART_REQUIRED', 401);
      }
    } else {
      pair.viewer = input.ownerAuthority as OwnerAuthority; pair.proof = randomToken(); await this.save(pairs);
    }
    return pair;
  }
  async decide(input: Record<string, unknown>): Promise<BrowserPair> {
    const auth = new OwnerAuth(this.storage, this.env);
    await auth.validateAuthority(input.ownerAuthority);
    const pairs = await this.load(), pair = this.find(pairs, requestId(input));
    const current = input.ownerAuthority as OwnerAuthority;
    if (!pair.viewer || pair.viewer.sessionHash !== current.sessionHash || pair.viewer.browserHash !== current.browserHash ||
        typeof input.proof !== 'string' || !pair.proof || !constantTime(pair.proof, input.proof)) {
      throw new ControlError('INVALID_PAIRING_APPROVAL', 403);
    }
    await auth.validateAuthority(pair.viewer);
    if (pair.expiresAt <= Date.now() || pair.status !== 'pending') throw new ControlError('PAIRING_EXPIRED_OR_USED', 409);
    assert(input.decision === 'allow' || input.decision === 'deny', 'EXPLICIT_DECISION_REQUIRED');
    if (input.decision === 'allow') {
      assert(input.comparison_confirmed === true, 'PAIRING_COMPARISON_REQUIRED');
      pair.status = 'approved'; pair.approver = current;
    } else {pair.status = 'denied';}
    await this.save(pairs); return pair;
  }
  async prepareFinish(input: Record<string, unknown>): Promise<FinishPreparation> {
    const {value, id} = await this.prove('finish', input), pairs = await this.load(), pair = this.find(pairs, id);
    const devices = await this.devices(), old = this.checkDevice(devices, value);
    if (pair.status === 'complete' || pair.status === 'committed') {
      if (!old) throw new ControlError('PAIRING_REJECTED', 401);
      return {pair, registration: old, done: pair.status === 'complete' && !old.pending};
    }
    if (pair.status !== 'approved' || pair.expiresAt <= Date.now()) throw new ControlError('PAIRING_NOT_APPROVED', 401);
    await new OwnerAuth(this.storage, this.env).validateAuthority(pair.approver);
    if (old?.pending) throw new ControlError('PAIRING_CONFLICT', 409);
    if (!old && (devices.filter(d => !d.revoked).length >= 16 || devices.length >= 256)) throw new ControlError('DEVICE_LIMIT', 409);
    const registration = old ?? {deviceId: value.device_id, publicKey: value.public_key, name: value.name,
      ownerId: this.env.OWNER_ID, pairedAt: Date.now(), revoked: false, pending: true};
    pair.status = old ? 'complete' : 'committed'; pair.retainUntil = Date.now() + RECOVERY_TTL;
    // Authorization linearizes here. A retry recovers this committed registration;
    // it does not perform another authorization after logout/expiry. A later revoke still wins.
    await this.storage.transaction(async tx => {
      await tx.put(KEY, pairs);
      if (!old) await tx.put('devices', [...devices, registration]);
    });
    const alarm = await this.storage.getAlarm();
    if (alarm === null) await this.storage.setAlarm(Date.now() + 360_000);
    return {pair, registration, done: !!old};
  }
  async complete(id: string): Promise<Record<string, unknown>> {
    const pairs = await this.load(), pair = this.find(pairs, id), devices = await this.devices();
    const old = this.checkDevice(devices, pair);
    if (!old || !['committed', 'complete'].includes(pair.status)) throw new ControlError('PAIRING_REJECTED', 401);
    pair.status = 'complete';
    await this.storage.transaction(async tx => {
      await tx.put(KEY, pairs);
      await tx.put('devices', devices.map(d => d.deviceId === pair.device_id ? {...d, pending: false} : d));
    });
    return pairingResult(pair);
  }
}
