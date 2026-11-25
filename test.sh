#!/usr/bin/env bash
#
# Run the clojure.test tests.
#
# Usage:
#   ./test.sh                  ; every namespace that defines a deftest
#   ./test.sh clodin.names     ; only the named namespaces
#   ./test.sh --help           ; this text
#
# The tests themselves are run by scripts/run_tests.clj through the :test
# alias in deps.edn. This wrapper only makes sure target/current points at
# the LLVM bindings for this machine: the bindings are generated per
# platform and the workspace is shared with the macOS host, so the active
# symlink often names the other one.
#
# Exits with the number of failures and errors, so a shell can gate on it.
#
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$root_dir"

usage() {
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
}

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

case "${1:-}" in
    --help|-h)
        usage
        exit 0
        ;;
esac

case "$(uname -s)" in
    Darwin)
        platform_os="macos"
        ;;
    Linux)
        platform_os="linux"
        ;;
    *)
        die "unsupported operating system: $(uname -s)"
        ;;
esac

case "$(uname -m)" in
    arm64|aarch64)
        platform_arch="aarch64"
        ;;
    x86_64|amd64)
        platform_arch="x64"
        ;;
    *)
        die "unsupported architecture: $(uname -m)"
        ;;
esac

platform="$platform_os-$platform_arch"
current="$root_dir/target/current"

classes="$root_dir/target/classes/$platform"
[ -d "$classes/clodin/llvm" ] || \
    die "no LLVM bindings for $platform at $classes; run scripts/generate-llvm-bindings.sh first"

# The workspace is shared with the macOS host, so the link may name the
# other platform, or be dangling while the other side swaps it.
active="$(readlink "$current" 2>/dev/null || true)"
if [ "${active##*/}" != "$platform" ]; then
    printf 'Switching LLVM bindings to %s (was %s)\n' \
        "$platform" "${active:-nothing}" >&2
    "$root_dir/scripts/use-llvm-bindings.sh" "$platform" >&2
fi

exec clojure -M:test "$@"
