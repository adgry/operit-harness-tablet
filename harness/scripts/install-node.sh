#!/usr/bin/env bash
# Caller holds the runtime install lock; no final directory contains partial extraction.
set -euo pipefail
archive="$1"; node_dir="$2"; version="$3"; digest="$4"
verify_node() {
  [[ -x "$1/bin/node" && -f "$1/lib/node_modules/npm/bin/npm-cli.js" ]] &&
    [[ "$("$1/bin/node" --version)" == "v$version" ]] &&
    "$1/bin/node" "$1/lib/node_modules/npm/bin/npm-cli.js" --version >/dev/null
}
if [[ -f "$node_dir/.operit-complete" ]] && [[ "$(cat "$node_dir/.operit-complete")" == "$digest" ]] && verify_node "$node_dir"; then
  exit 0
fi
# Interrupted staging never becomes an installed runtime. Under the exclusive lock,
# abandoned staging directories and old unvalidated installs can be safely replaced.
for old in "$node_dir".staging.*; do [[ ! -e "$old" ]] || rm -rf -- "$old"; done
stage="$(mktemp -d "$node_dir.staging.XXXXXXXX")"
trap 'rm -rf -- "$stage"' EXIT
python3 - "$archive" "$digest" <<'VERIFY'
import hashlib, sys
with open(sys.argv[1], 'rb') as archive:
    actual = hashlib.file_digest(archive, 'sha256').hexdigest()
if actual != sys.argv[2]:
    sys.exit('Node archive SHA256 mismatch')
VERIFY
tar -xJf "$archive" --strip-components=1 -C "$stage"
verify_node "$stage" || { echo 'Node extraction failed runtime/npm validation' >&2; exit 1; }
printf '%s\n' "$digest" > "$stage/.operit-complete"
# A legacy partial directory has no trusted marker. It must not block a retry.
rm -rf -- "$node_dir"
mv -- "$stage" "$node_dir"
