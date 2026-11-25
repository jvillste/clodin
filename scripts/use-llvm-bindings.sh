#!/usr/bin/env bash
#
# Point target/current at the LLVM bindings for this platform.
#
# This does not download jextract or regenerate anything. Use it when the
# bindings for another platform were generated last, for example after the
# Linux container switched target/current to linux-aarch64.
#
# Usage:
#   scripts/use-llvm-bindings.sh [platform]
#
# The optional platform argument is useful for switching explicitly, e.g.
# macos-aarch64 or linux-aarch64. Without it, the current OS and
# architecture are detected.
#
set -euo pipefail

usage() {
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
}

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

case "${1:-}" in
    --help|-h)
        usage
        exit 0
        ;;
    -*)
        die "unknown option: $1"
        ;;
esac

if [ "$#" -gt 0 ]; then
    platform="$1"
else
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
fi

classes="$root_dir/target/classes/$platform"
current="$root_dir/target/current"

[ -d "$classes" ] || die "no LLVM bindings for $platform at $classes; run scripts/generate-llvm-bindings.sh first"

if [ -L "$current" ]; then
    rm -f "$current"
elif [ -e "$current" ]; then
    rm -rf "$current"
fi

mkdir -p "$(dirname "$current")"
ln -s "classes/$platform" "$current"

printf 'Active LLVM bindings: %s -> classes/%s\n' "$current" "$platform"

# The generated bindings bake the absolute path of the libLLVM they were
# created from into a class initializer. A directory generated on another
# machine, or against an LLVM install that has since moved, fails at load
# time with "Cannot open library". Report that here instead.
baked_libraries="$(
    find "$classes" -type f -name '*.class' \
        -exec grep -aoh -E '/[A-Za-z0-9._@+/-]*libLLVM[A-Za-z0-9._@+-]*' {} + 2>/dev/null \
        | sort -u || true
)"

missing_libraries=""
while IFS= read -r library; do
    [ -n "$library" ] || continue
    [ -e "$library" ] || missing_libraries="${missing_libraries}${library}"$'\n'
done <<< "$baked_libraries"

if [ -n "$missing_libraries" ]; then
    printf 'warning: the active bindings were generated against a libLLVM that is missing here:\n' >&2
    while IFS= read -r library; do
        [ -n "$library" ] && printf '  %s\n' "$library" >&2
    done <<< "$missing_libraries"
    printf 'warning: regenerate them with scripts/generate-llvm-bindings.sh (LLVM_LIBRARY=...)\n' >&2
fi
