#!/usr/bin/env bash
# End-to-end check of `aether-ca` against the live Supabase certificate registry.
#
# Creates a throwaway CA and device certificate labelled "SMOKE-TEST-<time>", then checks
# register -> status valid -> verify --online -> revoke -> status revoked -> verify --online fails.
# Rows it leaves are labelled SMOKE-TEST-* and are safe to delete.
#
# The service-role key is read with hidden input (or from AETHER_SUPABASE_SERVICE_KEY if
# already set) and never printed or written to disk.
#
#   bash tools/registry-smoke.sh
#
# Without the key, against the local PostgREST stand-in (tools/registry-mock.py):
#   python tools/registry-mock.py 54329 &
#   AETHER_SUPABASE_URL=http://127.0.0.1:54329 AETHER_SUPABASE_SERVICE_KEY=sb_secret_mock bash tools/registry-smoke.sh
set -uo pipefail
cd "$(dirname "$0")/../core" || exit 1
export PATH="$HOME/tools/winlibs/mingw64/bin:$PATH"

if [ -z "${AETHER_SUPABASE_SERVICE_KEY:-}" ]; then
  read -rsp "Supabase service-role key (input hidden): " AETHER_SUPABASE_SERVICE_KEY
  echo
fi
export AETHER_SUPABASE_SERVICE_KEY
[ -n "$AETHER_SUPABASE_SERVICE_KEY" ] || { echo "SMOKE: no key given"; exit 1; }

cargo build -q --features registry --bin aether-ca || { echo "SMOKE: build failed"; exit 1; }
CA=target/debug/aether-ca
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
LABEL="SMOKE-TEST-$(date +%Y%m%d%H%M%S)"
pass=0; fail=0
check() { if [ "$1" = ok ]; then echo "PASS  $2"; pass=$((pass+1)); else echo "FAIL  $2  ($3)"; fail=$((fail+1)); fi; }
field() { python -c "import json,sys; print(json.loads(sys.argv[1]).get(sys.argv[2], ''))" "$1" "$2" 2>/dev/null; }

out=$($CA init --key "$TMP/ca.key" --register --label "$LABEL" 2>&1)
[ "$(field "$out" registered)" = True ] && check ok "CA registered" || check no "CA registered" "$out"
CAPUB=$(field "$out" ca_public_key)

DEV=$(python -c "import os; print(os.urandom(32).hex())")
out=$($CA sign --key "$TMP/ca.key" --device "$DEV" --label "$LABEL" --profile node --expiry 1h --register 2>&1)
[ "$(field "$out" registered)" = True ] && check ok "certificate recorded, device enrolled" || check no "certificate recorded" "$out"
CERT=$(field "$out" cert); SERIAL=$(field "$out" serial)

out=$($CA status --serial "$SERIAL" 2>&1)
[ "$(field "$out" status)" = valid ] && check ok "status valid" || check no "status valid" "$out"

out=$($CA verify --ca "$CAPUB" --device "$DEV" --cert "$CERT" --online 2>&1)
[ "$(field "$out" valid)" = True ] && check ok "verify --online accepts" || check no "verify --online accepts" "$out"

out=$($CA revoke --serial "$SERIAL" --reason "smoke test" 2>&1)
[ "$(field "$out" revoked)" = True ] && check ok "revoked" || check no "revoked" "$out"

out=$($CA status --serial "$SERIAL" 2>&1)
[ "$(field "$out" status)" = revoked ] && check ok "status revoked" || check no "status revoked" "$out"

out=$($CA verify --ca "$CAPUB" --device "$DEV" --cert "$CERT" --online 2>&1)
case "$out" in *"is revoked in the registry"*) check ok "verify --online refuses revoked";; *) check no "verify --online refuses revoked" "$out";; esac

echo "SMOKE RESULT: $pass passed, $fail failed (label $LABEL)"
