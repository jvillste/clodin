#!/usr/bin/env bash
#
# Download jextract and generate the LLVM C API bindings used by Clodin.
#
# Usage:
#   scripts/generate-llvm-bindings.sh [--force]
#
# Environment overrides:
#   JEXTRACT_VERSION       jextract build, default 25-jextract+2-4
#   JEXTRACT_URL           full tarball URL; overrides JEXTRACT_VERSION
#   JEXTRACT_BIN           existing jextract executable; skips download
#   JEXTRACT_HOME          where to unpack jextract, default .clodin/jextract/<version>
#   JEXTRACT_PACKAGE       Java package, default clodin.llvm
#   JEXTRACT_HEADER_CLASS  generated header class, default LLVM
#   JEXTRACT_OUTPUT        generated Java sources, default target/generated-sources/jextract
#   JEXTRACT_CLASSES       compiled classes, default target/classes
#   JEXTRACT_LIBRARY       --library argument, default :<discovered libLLVM>;
#                          use "none" to omit --library and rely on loaderLookup
#   LLVM_INCLUDE_DIR       directory containing llvm-c/Core.h
#   LLVM_LIBRARY           path to libLLVM.so / libLLVM.dylib
#   FORCE=1                same as --force
#
# The generated Java is platform-specific. Run this script on each platform
# where Clodin is used. On macOS, Homebrew LLVM is the easiest source of both
# the headers and libLLVM.dylib:
#
#   brew install llvm
#
set -euo pipefail

usage() {
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
}

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

force="${FORCE:-0}"
while [ "$#" -gt 0 ]; do
    case "$1" in
        --force)
            force=1
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            die "unknown argument: $1"
            ;;
    esac
    shift
done

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

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

case "$platform_arch" in
    aarch64)
        llvm_target="AArch64"
        ;;
    x64)
        llvm_target="X86"
        ;;
esac

jextract_version="${JEXTRACT_VERSION:-25-jextract+2-4}"
jextract_package="${JEXTRACT_PACKAGE:-clodin.llvm}"
jextract_header_class="${JEXTRACT_HEADER_CLASS:-LLVM}"
jextract_output="${JEXTRACT_OUTPUT:-$root_dir/target/generated-sources/jextract}"
jextract_classes="${JEXTRACT_CLASSES:-$root_dir/target/classes}"

if [ -z "${JEXTRACT_URL:-}" ]; then
    case "$jextract_version" in
        *-jextract+*-*)
            ;;
        *)
            die "JEXTRACT_VERSION must look like 25-jextract+2-4, got: $jextract_version"
            ;;
    esac
    jextract_major="${jextract_version%%-*}"
    jextract_build="${jextract_version#*+}"
    jextract_build_number="${jextract_build%%-*}"
    JEXTRACT_URL="https://download.java.net/java/early_access/jextract/${jextract_major}/${jextract_build_number}/openjdk-${jextract_version}_${platform_os}-${platform_arch}_bin.tar.gz"
fi

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        die "need sha256sum or shasum to verify the jextract download"
    fi
}

download_jextract() {
    local download_dir archive checksum_file expected actual install_dir
    download_dir="$root_dir/.clodin/jextract/downloads"
    archive="$download_dir/$(basename "$JEXTRACT_URL")"
    checksum_file="$archive.sha256"
    install_dir="${JEXTRACT_HOME:-$root_dir/.clodin/jextract/$jextract_version}"

    mkdir -p "$download_dir"

    if [ "$force" = "1" ] || [ ! -f "$archive" ]; then
        printf 'Downloading %s\n' "$JEXTRACT_URL" >&2
        curl -fL --retry 3 --retry-delay 2 -o "$archive.part" "$JEXTRACT_URL"
        mv "$archive.part" "$archive"
    fi

    if [ "$force" = "1" ] || [ ! -f "$checksum_file" ]; then
        curl -fL --retry 3 --retry-delay 2 -o "$checksum_file.part" "${JEXTRACT_URL}.sha256"
        mv "$checksum_file.part" "$checksum_file"
    fi

    expected="$(awk 'NR == 1 {print $1}' "$checksum_file")"
    [ -n "$expected" ] || die "empty checksum file: $checksum_file"
    actual="$(sha256_file "$archive")"
    [ "$expected" = "$actual" ] || die "checksum mismatch for $archive"

    local existing_binary
    existing_binary="$(find "$install_dir" -type f -name jextract 2>/dev/null | head -1)"
    if [ "$force" = "1" ] || [ -z "$existing_binary" ]; then
        rm -rf "$install_dir"
        mkdir -p "$install_dir"
        tar -xzf "$archive" -C "$install_dir"
        if [ "$platform_os" = "macos" ] && command -v xattr >/dev/null 2>&1; then
            xattr -dr com.apple.quarantine "$install_dir" 2>/dev/null || true
        fi
    fi

    local candidate
    candidate="$(find "$install_dir" -type f -name jextract | head -1)"
    [ -n "$candidate" ] || die "could not find the jextract executable under $install_dir"
    chmod +x "$candidate"
    printf '%s\n' "$candidate"
}

if [ -n "${JEXTRACT_BIN:-}" ]; then
    [ -x "$JEXTRACT_BIN" ] || die "JEXTRACT_BIN is not executable: $JEXTRACT_BIN"
    jextract_bin="$JEXTRACT_BIN"
else
    jextract_bin="$(download_jextract)"
fi

find_llvm_include() {
    if [ -n "${LLVM_INCLUDE_DIR:-}" ]; then
        [ -f "$LLVM_INCLUDE_DIR/llvm-c/Core.h" ] || \
            die "LLVM_INCLUDE_DIR does not contain llvm-c/Core.h: $LLVM_INCLUDE_DIR"
        printf '%s\n' "$LLVM_INCLUDE_DIR"
        return
    fi

    local candidates=""
    local add_candidate
    add_candidate() {
        [ -n "$1" ] && candidates="${candidates}${1}"$'\n'
    }

    if [ "$platform_os" = "macos" ]; then
        if command -v brew >/dev/null 2>&1; then
            local brew_prefix
            brew_prefix="$(brew --prefix llvm 2>/dev/null || true)"
            add_candidate "$brew_prefix/include"
        fi
        add_candidate "/opt/homebrew/opt/llvm/include"
        add_candidate "/usr/local/opt/llvm/include"
    else
        local directory
        while IFS= read -r directory; do
            add_candidate "$directory"
        done <<< "$(ls -d /usr/lib/llvm-*/include 2>/dev/null || true)"
        add_candidate "/usr/include"
        add_candidate "/usr/local/include"
    fi
    add_candidate "$root_dir/references/llvm-project/llvm/include"

    local candidate
    while IFS= read -r candidate; do
        if [ -n "$candidate" ] && [ -f "$candidate/llvm-c/Core.h" ]; then
            printf '%s\n' "$candidate"
            return
        fi
    done <<< "$candidates"

    die "could not find llvm-c headers; install LLVM or set LLVM_INCLUDE_DIR"
}

find_llvm_library() {
    if [ -n "${LLVM_LIBRARY:-}" ]; then
        [ -f "$LLVM_LIBRARY" ] || die "LLVM_LIBRARY does not exist: $LLVM_LIBRARY"
        printf '%s\n' "$LLVM_LIBRARY"
        return
    fi

    local candidates=""
    local add_candidate
    add_candidate() {
        [ -n "$1" ] && candidates="${candidates}${1}"$'\n'
    }

    if [ "$platform_os" = "macos" ]; then
        local brew_prefix
        if command -v brew >/dev/null 2>&1; then
            brew_prefix="$(brew --prefix llvm 2>/dev/null || true)"
            add_candidate "$brew_prefix/lib/libLLVM.dylib"
        fi
        add_candidate "/opt/homebrew/opt/llvm/lib/libLLVM.dylib"
        add_candidate "/usr/local/opt/llvm/lib/libLLVM.dylib"
        local library
        for library in /opt/homebrew/opt/llvm/lib/libLLVM*.dylib \
                         /usr/local/opt/llvm/lib/libLLVM*.dylib; do
            [ -e "$library" ] && add_candidate "$library"
        done
    else
        local library
        for library in /usr/lib/llvm-*/lib/libLLVM.so \
                         /usr/lib/*/libLLVM.so* \
                         /usr/lib/libLLVM.so* \
                         /usr/local/lib/libLLVM.so*; do
            [ -e "$library" ] && add_candidate "$library"
        done
    fi

    local candidate
    while IFS= read -r candidate; do
        if [ -n "$candidate" ] && [ -f "$candidate" ]; then
            printf '%s\n' "$candidate"
            return
        fi
    done <<< "$candidates"

    die "could not find libLLVM; install LLVM or set LLVM_LIBRARY"
}

llvm_include="$(find_llvm_include)"
llvm_library="$(find_llvm_library)"

case "$llvm_include" in
    "$root_dir/references/"*)
        printf 'warning: using vendored LLVM headers; make sure they match %s\n' \
            "$llvm_library" >&2
        ;;
esac

headers=(
    "$llvm_include/llvm-c/Analysis.h"
    "$llvm_include/llvm-c/BitReader.h"
    "$llvm_include/llvm-c/BitWriter.h"
    "$llvm_include/llvm-c/Core.h"
    "$llvm_include/llvm-c/Error.h"
    "$llvm_include/llvm-c/Linker.h"
    "$llvm_include/llvm-c/LLJIT.h"
    "$llvm_include/llvm-c/Orc.h"
    "$llvm_include/llvm-c/Support.h"
    "$llvm_include/llvm-c/Target.h"
    "$llvm_include/llvm-c/TargetMachine.h"
    "$llvm_include/llvm-c/Transforms/PassBuilder.h"
)

for header in "${headers[@]}"; do
    [ -f "$header" ] || die "missing LLVM header: $header"
done

jextract_library="${JEXTRACT_LIBRARY:-:$llvm_library}"

rm -rf "$jextract_output"
mkdir -p "$jextract_output" "$jextract_classes"

include_arguments=(--include-dir "$llvm_include")

# The vendored LLVM source tree does not contain the generated
# llvm/Config/llvm-config.h or the llvm/Config/*.def target lists.
# Build a small include shim for those files when the selected headers
# lack them. System LLVM development packages already provide them.
if [ ! -f "$llvm_include/llvm/Config/llvm-config.h" ]; then
    llvm_config_shim="$root_dir/target/generated-sources/llvm-config-shim"
    llvm_config_source="$root_dir/references/llvm-project/utils/bazel/llvm-project-overlay/llvm/include/llvm/Config/llvm-config.h"
    [ -f "$llvm_config_source" ] || \
        die "missing llvm/Config/llvm-config.h in $llvm_include and no config shim source found"
    rm -rf "$llvm_config_shim"
    mkdir -p "$llvm_config_shim/llvm/Config"
    cp "$llvm_config_source" "$llvm_config_shim/llvm/Config/llvm-config.h"
    printf 'LLVM_TARGET(%s)\n' "$llvm_target" > "$llvm_config_shim/llvm/Config/Targets.def"
    printf 'LLVM_ASM_PRINTER(%s)\n' "$llvm_target" > "$llvm_config_shim/llvm/Config/AsmPrinters.def"
    printf 'LLVM_ASM_PARSER(%s)\n' "$llvm_target" > "$llvm_config_shim/llvm/Config/AsmParsers.def"
    printf 'LLVM_DISASSEMBLER(%s)\n' "$llvm_target" > "$llvm_config_shim/llvm/Config/Disassemblers.def"
    include_arguments=(--include-dir "$llvm_config_shim" --include-dir "$llvm_include")
fi

jextract_arguments=(
    "${include_arguments[@]}"
    --output "$jextract_output"
    --target-package "$jextract_package"
    --header-class-name "$jextract_header_class"
)

if [ "$jextract_library" != "none" ]; then
    jextract_arguments+=(--library "$jextract_library")
fi

jextract_arguments+=("${headers[@]}")

printf 'Using jextract: %s\n' "$jextract_bin"
printf 'Using headers:  %s\n' "$llvm_include"
printf 'Using library:  %s\n' "$llvm_library"
printf 'Generating:     %s\n' "$jextract_output"

"$jextract_bin" "${jextract_arguments[@]}"

java_files="$(find "$jextract_output" -type f -name '*.java' | wc -l | tr -d ' ')"
[ "$java_files" != "0" ] || die "jextract produced no Java files in $jextract_output"

printf 'Compiling %s Java files into %s\n' "$java_files" "$jextract_classes"
find "$jextract_output" -type f -name '*.java' -print0 | \
    xargs -0 javac -d "$jextract_classes"

printf 'Done.\n'
