import {build} from 'esbuild';
import {mkdir, writeFile} from 'node:fs/promises';
const result = await build({entryPoints: ['web/passkeys.js'], bundle: true, write: false,
  platform: 'browser', format: 'iife', target: ['es2022'], minify: true, legalComments: 'none'});
await mkdir('.generated', {recursive: true});
// Embed as a Worker string, with no CDN/extra runtime module or inline HTML script.
await writeFile('.generated/passkeys-browser.ts', 'export default ' + JSON.stringify(result.outputFiles[0].text) + ';\n');
