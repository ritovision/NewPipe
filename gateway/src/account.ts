import { DurableObject } from 'cloudflare:workers';
import { assert, body, CLIENT, ControlError, device, digest, Env, failure, randomToken, response, UUID, verifyDevice, DeviceRegistration, cleanLabel } from './shared';

interface Pairing { expiresAt: number; deviceId?: string; fingerprint?: string }
type Registration = DeviceRegistration & { pending?: boolean };

export class ControlAccount extends DurableObject<Env> {
  /** Expected validation failures must never escape blockConcurrencyWhile and reset this object. */
  private async gate<T>(action: () => Promise<T>): Promise<T> {
    const settled = await this.ctx.blockConcurrencyWhile(async () => {
      try { return {ok: true as const, value: await action()}; }
      catch (error) { return {ok: false as const, error}; }
    });
    if (!settled.ok) throw settled.error;
    return settled.value;
  }

  async fetch(request: Request): Promise<Response> {
    try {
      const path = new URL(request.url).pathname;
      if (path === '/list') return response({
        devices: await this.ctx.storage.get<Registration[]>('devices') ?? [],
        blocked_clients: await this.ctx.storage.get<string[]>('blocked') ?? []
      });
      const input = await body(request, 16_384);
      if (path === '/pairing') {
        const code = randomToken(18), expiresAt = Date.now() + 300_000;
        await this.gate(async () => {
          const current = await this.ctx.storage.list<Pairing>({prefix: 'pair:', limit: 65});
          if (current.size >= 64) throw new ControlError('PAIRING_LIMIT', 429);
          await this.ctx.storage.put('pair:' + await digest(code), {expiresAt});
          await this.ctx.storage.setAlarm(Date.now() + 360_000);
        });
        return response({pairing_code: code, expires_at_ms: expiresAt});
      }
      if (path === '/pair') {
        assert(typeof input.pairing_code === 'string' && /^[A-Za-z0-9_-]{24}$/.test(input.pairing_code));
        assert(typeof input.device_id === 'string' && UUID.test(input.device_id));
        assert(typeof input.public_key === 'string' && input.public_key.length <= 256);
        assert(typeof input.name === 'string' && input.name.trim().length > 0 && input.name.length <= 80);
        assert(typeof input.signature === 'string' && input.signature.length <= 128);
        const message = `pair\n${this.env.PUBLIC_ORIGIN}\n${input.device_id}\n${input.pairing_code}\n${input.public_key}\n${input.name}`;
        if (!await verifyDevice(input.public_key, message, input.signature)) throw new ControlError('NOT_AUTHORIZED', 401);
        const codeKey = 'pair:' + await digest(input.pairing_code), fingerprint = await digest(message);
        const registration: Registration = {
          deviceId: input.device_id, name: cleanLabel(input.name), publicKey: input.public_key,
          ownerId: this.env.OWNER_ID, revoked: false, pairedAt: Date.now(), pending: true
        };
        const prepared = await this.gate(async () => {
          const pairing = await this.ctx.storage.get<Pairing>(codeKey);
          if (!pairing || pairing.expiresAt <= Date.now() || (pairing.deviceId && pairing.fingerprint !== fingerprint)) {
            throw new ControlError('PAIRING_EXPIRED_OR_USED', 401);
          }
          const devices = await this.ctx.storage.get<Registration[]>('devices') ?? [];
          const old = devices.find(d => d.deviceId === registration.deviceId);
          if (old?.revoked || (old && old.publicKey !== registration.publicKey)) throw new ControlError('PAIRING_REJECTED', 401);
          if (!old && (devices.filter(d => !d.revoked).length >= 16 || devices.length >= 256)) throw new ControlError('DEVICE_LIMIT', 409);
          if (old && !old.pending) {
            // Successful retries do not rewrite the registration or remove a later revocation.
            if (!pairing.deviceId) await this.ctx.storage.put(codeKey, {...pairing, deviceId: registration.deviceId, fingerprint});
            return {registration: old, done: true};
          }
          const next = old ?? registration;
          await this.ctx.storage.transaction(async tx => {
            await tx.put(codeKey, {...pairing, deviceId: registration.deviceId, fingerprint});
            await tx.put('devices', [...devices.filter(d => d.deviceId !== registration.deviceId), next]);
          });
          return {registration: next, done: false};
        });
        if (!prepared.done) {
          // No network or cross-object wait under the account concurrency barrier.
          const result = await device(this.env, registration.deviceId).fetch(new Request('https://internal/register', {
            method: 'POST', body: JSON.stringify(prepared.registration)
          }));
          if (!result.ok) throw new ControlError('PAIRING_CONFLICT', 409);
          await this.gate(async () => {
            const devices = await this.ctx.storage.get<Registration[]>('devices') ?? [];
            const current = devices.find(d => d.deviceId === registration.deviceId);
            if (!current || current.revoked) throw new ControlError('PAIRING_REJECTED', 401);
            await this.ctx.storage.put('devices', devices.map(d => d.deviceId === registration.deviceId ? {...d, pending: false} : d));
          });
        }
        return response({paired: true, device_id: registration.deviceId, owner_id: this.env.OWNER_ID, protocol_version: 1});
      }
      if (path === '/revoke-device') {
        assert(typeof input.device_id === 'string' && UUID.test(input.device_id));
        const id = input.device_id;
        await this.gate(async () => {
          const devices = await this.ctx.storage.get<Registration[]>('devices') ?? [];
          if (!devices.some(d => d.deviceId === id)) throw new ControlError('NOT_FOUND', 404);
          await this.ctx.storage.put('devices', devices.map(d => d.deviceId === id ? {...d, revoked: true, pending: false} : d));
          const pairs = await this.ctx.storage.list<Pairing>({prefix: 'pair:'});
          const used = [...pairs].filter(([, value]) => value.deviceId === id).map(([key]) => key);
          for (let n = 0; n < used.length; n += 128) await this.ctx.storage.delete(used.slice(n, n + 128));
        });
        const result = await device(this.env, id).fetch(new Request('https://internal/revoke', {method: 'POST', body: '{}'}));
        if (!result.ok) throw new ControlError('DEVICE_REVOKE_PENDING', 503);
        return response({revoked: true});
      }
      if (path === '/block-client' || path === '/unblock-client') {
        assert(typeof input.client_id === 'string' && CLIENT.test(input.client_id));
        const client = input.client_id, blocked = path === '/block-client';
        const policy = await this.gate(async () => {
          const previous = await this.ctx.storage.get<string[]>('blocked') ?? [];
          if (blocked && !previous.includes(client) && previous.length >= 128) throw new ControlError('CLIENT_LIMIT', 409);
          const revision = (await this.ctx.storage.get<number>('policy_revision:'+client) ?? 0) + 1;
          if(!Number.isSafeInteger(revision)) throw new ControlError('POLICY_VERSION_LIMIT',409);
          await this.ctx.storage.transaction(async tx=>{
            await tx.put('blocked', blocked ? [...new Set([...previous, client])] : previous.filter(c => c !== client));
            await tx.put('policy_revision:'+client,revision);
          });
          return {revision,devices:(await this.ctx.storage.get<Registration[]>('devices') ?? []).filter(d => !d.revoked && !d.pending)};
        });
        const results = await Promise.all(policy.devices.map(d => device(this.env, d.deviceId).fetch(new Request('https://internal/client-policy', {
          method: 'POST', body: JSON.stringify({client_id: client, blocked, revision:policy.revision})
        }))));
        if (results.some(r => !r.ok)) throw new ControlError('CLIENT_POLICY_PENDING', 503);
        return response({blocked});
      }
      throw new ControlError('NOT_FOUND', 404);
    } catch (error) { return failure(error); }
  }

  async alarm(): Promise<void> {
    try {
      await this.gate(async () => {
        const pairs = await this.ctx.storage.list<Pairing>({prefix: 'pair:'});
        const expired = [...pairs].filter(([, p]) => p.expiresAt <= Date.now()).map(([key]) => key);
        for (let n = 0; n < expired.length; n += 128) await this.ctx.storage.delete(expired.slice(n, n + 128));
        if (pairs.size > expired.length) await this.ctx.storage.setAlarm(Date.now() + 360_000);
      });
    } catch { await this.ctx.storage.setAlarm(Date.now() + 60_000); }
  }
}
