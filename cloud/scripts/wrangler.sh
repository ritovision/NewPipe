#!/bin/sh
set -eu
: "${HOME:?}"
export XDG_CONFIG_HOME="$HOME/.config/ritovision/newpipe-cloud"
export CLOUDFLARE_ACCOUNT_ID=b35814b119cba4448e3e0ac59442cc25
export WRANGLER_SEND_METRICS=false
export BROWSER=/bin/false
unset CLOUDFLARE_API_TOKEN CF_API_TOKEN CLOUDFLARE_API_KEY CLOUDFLARE_EMAIL
cd "$(dirname "$0")/.."
# A named profile also isolates hosts where legacy ~/.wrangler takes precedence over XDG.
exec wrangler --profile newpipe-cloud --config wrangler.jsonc "$@"
