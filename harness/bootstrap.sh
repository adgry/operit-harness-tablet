#!/usr/bin/env bash
set -euo pipefail
umask 077

asset_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
: "${OPERIT_RUNTIME_DIR:?An explicit private runtime directory is required}"
case "$OPERIT_RUNTIME_DIR" in /*) ;; *) echo 'Runtime path must be absolute' >&2; exit 2;; esac
runtime_dir="$OPERIT_RUNTIME_DIR"
action="${1:?Use install, start, or stop}"
# Generated from upstream-lock.json by sync-assets.mjs; values are validated there.
source "$asset_dir/node.env"
node_dir="$runtime_dir/node-$NODE_VERSION"
engine_dir="$runtime_dir/engine-$DSH_VERSION"
export PATH="$node_dir/bin:$engine_dir/node_modules/.bin:$PATH"
export DSH_HOME="$runtime_dir/data"
export npm_config_cache="$runtime_dir/cache/npm"
export npm_config_devdir="$runtime_dir/cache/node-gyp"
export npm_config_store_dir="$runtime_dir/cache/pnpm"
export XDG_CACHE_HOME="$runtime_dir/cache"
export TMPDIR="$runtime_dir/tmp"
mkdir -p "$runtime_dir" "$TMPDIR" "$npm_config_cache"

die() { echo "operit-harness: $*" >&2; exit 1; }
verify() { echo "$2  $1" | sha256sum -c >/dev/null || die "SHA256 verification failed: $(basename -- "$1")"; }

# The supervisor and official Node .xz archive need these Ubuntu prerequisites.
# Provision only missing packages, idempotently, before taking the Python lock.
if [[ "$action" == install ]]; then
  echo 'operit-harness: phase=prerequisites'
  [[ "$(uname -s)" == Linux ]] || die 'Install requires the Operit Ubuntu Linux environment'
  prerequisites=()
  command -v python3 >/dev/null || prerequisites+=(python3)
  command -v curl >/dev/null || prerequisites+=(curl ca-certificates)
  command -v xz >/dev/null || prerequisites+=(xz-utils)
  command -v tar >/dev/null || prerequisites+=(tar)
  command -v sha256sum >/dev/null || prerequisites+=(coreutils)
  if (( ${#prerequisites[@]} )); then
    command -v apt-get >/dev/null || die 'Ubuntu requires apt-get to provision runtime dependencies'
    echo "operit-harness: installing required Ubuntu packages: ${prerequisites[*]}"
    apt-get update
    apt-get install --no-install-recommends -y "${prerequisites[@]}"
  fi
fi
command -v python3 >/dev/null || die 'Ubuntu Python3 is missing; run install first'

case "$action" in
  install)
    # Re-enter only after acquiring an OS-held lock. File presence is irrelevant;
    # abrupt death releases the lock once any ongoing child write finishes.
    exec python3 "$asset_dir/scripts/runtime-lifecycle.py" lock "$runtime_dir/.install.lock" \
      bash "$asset_dir/bootstrap.sh" install-locked
    ;;
  install-locked)
    [[ "$(uname -s)" == Linux ]] || die 'Install requires the Operit Ubuntu Linux environment'
    command -v curl >/dev/null || die 'Ubuntu requires curl'
    command -v tar >/dev/null || die 'Ubuntu requires tar'
    command -v sha256sum >/dev/null || die 'Ubuntu requires sha256sum'
    case "$(uname -m)" in
      aarch64|arm64) arch=arm64; node_sha="$NODE_SHA_ARM64";;
      x86_64) arch=x64; node_sha="$NODE_SHA_X64";;
      *) die 'Unsupported Linux CPU architecture';;
    esac
    echo 'operit-harness: phase=node'
    if [[ -f "$node_dir/.operit-complete" && "$(cat "$node_dir/.operit-complete")" == "$node_sha" && -x "$node_dir/bin/node" && -x "$engine_dir/node_modules/.bin/dsh" && -f "$runtime_dir/installed-lock.json" ]] &&
        cmp -s "$asset_dir/upstream-lock.json" "$runtime_dir/installed-lock.json"; then
      node "$asset_dir/scripts/verify-installed.mjs" "$asset_dir" "$runtime_dir"
      # The persona payload is data, not a locked package: refresh it even on an
      # already-installed runtime so an app upgrade updates agent/skill files.
      node "$asset_dir/scripts/install-bairen.mjs" "$asset_dir" "$runtime_dir"
      echo 'operit-harness: locked runtime and plugins already installed'
      exit 0
    fi
    if [[ ! -f "$node_dir/.operit-complete" ]] ||
        [[ "$(cat "$node_dir/.operit-complete")" != "$node_sha" ]]; then
      archive="$runtime_dir/cache/node-v$NODE_VERSION-linux-$arch.tar.xz"
      curl --fail --location --proto '=https' --tlsv1.2 --connect-timeout 30 \
        "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-linux-$arch.tar.xz" --output "$archive.part"
      verify "$archive.part" "$node_sha"
      mv -- "$archive.part" "$archive"
      bash "$asset_dir/scripts/install-node.sh" "$archive" "$node_dir" "$NODE_VERSION" "$node_sha"
    fi
    "$node_dir/bin/node" --version
    node "$asset_dir/scripts/verify-packages.mjs" "$asset_dir"
    mkdir -p "$engine_dir"
    echo 'operit-harness: phase=engine'
    node "$asset_dir/scripts/install-engine.mjs" "$asset_dir" "$engine_dir"
    echo 'operit-harness: phase=plugins'
    node "$asset_dir/scripts/install-profile.mjs" "$asset_dir" "$engine_dir" "$runtime_dir"
    node "$asset_dir/scripts/install-bairen.mjs" "$asset_dir" "$runtime_dir"
    cp -- "$asset_dir/upstream-lock.json" "$runtime_dir/installed-lock.json"
    echo 'operit-harness: installation complete'
    ;;
  start)
    echo 'operit-harness: phase=start'
    [[ -x "$node_dir/bin/node" && -x "$engine_dir/node_modules/.bin/dsh" ]] || die 'Install the locked Harness runtime first'
    cmp -s "$asset_dir/upstream-lock.json" "$runtime_dir/installed-lock.json" || die 'Runtime/plugin versions changed; install the update first'
    node "$asset_dir/scripts/validate-bridge.mjs"
    exec python3 "$asset_dir/scripts/runtime-lifecycle.py" start "$runtime_dir" "$node_dir/bin/node" "$engine_dir"
    ;;
  stop)
    exec python3 "$asset_dir/scripts/runtime-lifecycle.py" stop "$runtime_dir" "$node_dir/bin/node" "$engine_dir"
    ;;
  *) die 'Use install, start, or stop';;
esac
