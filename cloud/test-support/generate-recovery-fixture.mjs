// SPDX-License-Identifier: GPL-3.0-or-later
// Regenerate the shared, synthetic TS/Kotlin recovery-format vectors.
import {readFile,writeFile} from 'node:fs/promises';
import {encodePages,pageManifest,byteLength} from '../src/pages.ts';
import {fingerprint} from '../src/catalog.ts';
import {cloudArchiveHeader,cloudArchivePage,bytesDigest,recoveryDigest} from '../src/recovery-format.ts';
const fixture=JSON.parse(await readFile(new URL('../../app/src/test/resources/cloud/golden-v1.json',import.meta.url),'utf8'));
const catalog=fixture.catalogs.find(c=>c.name==='base').catalog,pages=await encodePages(catalog);
const head={serviceGeneration:'fixture-service',library:'fixture-library',epoch:'fixture-epoch',revision:'7',headCounter:'12',fingerprint:await fingerprint(catalog)};
const source={head,pages:pages.length,bytes:pages.reduce((n,p)=>n+byteLength(p.body),0),manifest:await pageManifest(pages)};
const archive=cloudArchiveHeader(source)+pages.map(cloudArchivePage).join(''),bytes=Buffer.from(archive);
const manifest={version:1,kind:'cloud-catalog',bytes:bytes.length,source,native:null,producerValidation:'catalog-pages-v1',parts:[{bytes:bytes.length,sha256:await bytesDigest(bytes)}]};
const native={version:1,kind:'device-zip',bytes:140,source:null,native:{database:25,tags:7,uncompressedBytes:98304},producerValidation:'native-restore-v1',parts:[{bytes:140,sha256:'a'.repeat(64)}]};
await writeFile(new URL('../../app/src/test/resources/cloud/recovery-v1.json',import.meta.url),JSON.stringify({archive,catalog,manifest,digest:await recoveryDigest(manifest),nativeManifest:native,nativeDigest:await recoveryDigest(native)},null,2)+'\n');
