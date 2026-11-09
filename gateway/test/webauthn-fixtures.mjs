import {createHash, generateKeyPairSync, randomBytes, sign} from 'node:crypto';
import {origin,owner} from './helpers.mjs';
const sha=data=>createHash('sha256').update(data).digest();
// Minimal test-only CBOR encoder for an ES256 COSE key and 'none' attestation.
// Production verification/parsing is exclusively the actual SimpleWebAuthn library.
function head(major,n) {
 if(n<24)return Buffer.from([(major<<5)|n]);
 if(n<256)return Buffer.from([(major<<5)|24,n]);
 if(n<65536){const b=Buffer.alloc(3);b[0]=(major<<5)|25;b.writeUInt16BE(n,1);return b;}
 const b=Buffer.alloc(5);b[0]=(major<<5)|26;b.writeUInt32BE(n,1);return b;
}
function cbor(value) {
 if(typeof value==='number')return value>=0?head(0,value):head(1,-1-value);
 if(typeof value==='string'){const b=Buffer.from(value);return Buffer.concat([head(3,b.length),b]);}
 if(Buffer.isBuffer(value))return Buffer.concat([head(2,value.length),value]);
 if(value instanceof Map)return Buffer.concat([head(5,value.size),...[...value].flatMap(([k,v])=>[cbor(k),cbor(v)])]);
 throw new Error('Unsupported synthetic CBOR value');
}
export function authenticator({counter=0,synced=true,algorithm='ES256'}={}) {
 const {privateKey,publicKey}=algorithm==='RS256'?generateKeyPairSync('rsa',{modulusLength:2048,publicExponent:65537}):generateKeyPairSync('ec',{namedCurve:'prime256v1'});
 const id=randomBytes(32),idString=id.toString('base64url'),jwk=publicKey.export({format:'jwk'});
 const key=cbor(algorithm==='RS256'?new Map([[1,3],[3,-257],[-1,Buffer.from(jwk.n,'base64url')],[-2,Buffer.from(jwk.e,'base64url')]]):new Map([[1,2],[3,-7],[-1,1],[-2,Buffer.from(jwk.x,'base64url')],[-3,Buffer.from(jwk.y,'base64url')]]));
 function clientData(type,options,overrides) {
  return Buffer.from(JSON.stringify({type,challenge:options.challenge,origin,crossOrigin:false,...overrides.clientData}));
 }
 function authData(rp,flags,count,registration=false) {
  const countBytes=Buffer.alloc(4);countBytes.writeUInt32BE(count);
  const prefix=Buffer.concat([sha(rp),Buffer.from([flags]),countBytes]);
  if(!registration)return prefix;
  const length=Buffer.alloc(2);length.writeUInt16BE(id.length);
  return Buffer.concat([prefix,Buffer.alloc(16),length,id,key]);
 }
 const baseFlags=0x05|(synced?0x18:0);
 return {
  id:idString,privateKey,
  register(options,overrides={}) {
   const data=clientData('webauthn.create',options,overrides);
   const auth=authData(overrides.rp??options.rp.id,overrides.flags??(baseFlags|0x40),overrides.counter??counter,true);
   const att=cbor(new Map([['fmt','none'],['attStmt',new Map()],['authData',auth]]));
   return {id:idString,rawId:idString,type:'public-key',authenticatorAttachment:'platform',clientExtensionResults:{credProps:{rk:true}},
    response:{clientDataJSON:data.toString('base64url'),attestationObject:att.toString('base64url'),transports:['internal']}};
  },
  assert(options,overrides={}) {
   const data=clientData('webauthn.get',options,overrides);
   const auth=authData(overrides.rp??options.rpId,overrides.flags??baseFlags,overrides.counter??counter);
   const signature=sign('sha256',Buffer.concat([auth,sha(data)]),overrides.privateKey??privateKey);
   return {id:idString,rawId:idString,type:'public-key',authenticatorAttachment:'platform',clientExtensionResults:{},
    response:{clientDataJSON:data.toString('base64url'),authenticatorData:auth.toString('base64url'),signature:signature.toString('base64url'),
     userHandle:overrides.userHandle??Buffer.from(owner).toString('base64url')}};
  }
 };
}
