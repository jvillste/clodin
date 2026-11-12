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
