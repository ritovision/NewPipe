// SPDX-License-Identifier: GPL-3.0-or-later
import {fileURLToPath} from 'node:url';
// Workers has no DOMParser. Use the SDK's own pure-JS XML parser, not its DOM build.
// Absolute resolution also works when the generated Wrangler config is private.
export const operatorAliases = {'@aws-sdk/xml-builder':fileURLToPath(new URL('../node_modules/@aws-sdk/xml-builder/dist-cjs/index.js',import.meta.url))};
