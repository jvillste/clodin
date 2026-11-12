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
#   JEXTRACT_HOME          where to unpack jextract, default
#                          .clodin/jextract/<version>/<platform>
#   JEXTRACT_PACKAGE       Java package, default clodin.llvm
#   JEXTRACT_HEADER_CLASS  generated header class, default LLVM
#   JEXTRACT_OUTPUT        generated Java sources, default
#                          target/generated-sources/jextract/<platform>
#   JEXTRACT_CLASSES       compiled classes, default target/classes/<platform>
#   JEXTRACT_CURRENT       symlink to the active classes, default target/current
#   JEXTRACT_USE_BUNDLED_JAVA
#                          set to 1 to force jextract's bundled runtime
#   JEXTRACT_USE_SYSTEM_JAVA
#                          set to 1 to force the system JDK
#   JEXTRACT_LIBRARY       --library argument, default :<discovered libLLVM>;
#                          use "none" to omit --library and rely on loaderLookup
#   LLVM_INCLUDE_DIR       directory containing llvm-c/Core.h
#   LLVM_LIBRARY           path to libLLVM.so / libLLVM.dylib
#   FORCE=1                same as --force
#
# The generated Java is platform-specific. Each platform gets its own
# directory under target/, and target/current is a symlink to the
# active one. That lets the same deps.edn work on macOS and Linux.
#
# The macOS jextract binary must run on macOS. Because the workspace is
# shared, run this script on the Mac host in the same directory; it
# generates macOS bindings alongside the Linux ones and switches
# target/current to them. Run it in the container to switch back.
#
# To switch between already-generated bindings without regenerating,
# run scripts/use-llvm-bindings.sh.
#
# On macOS, Homebrew LLVM is the easiest source of both the headers and
# libLLVM.dylib:
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
jextract_platform="$platform_os-$platform_arch"
jextract_output="${JEXTRACT_OUTPUT:-$root_dir/target/generated-sources/jextract/$jextract_platform}"
jextract_classes="${JEXTRACT_CLASSES:-$root_dir/target/classes/$jextract_platform}"
jextract_current="${JEXTRACT_CURRENT:-$root_dir/target/current}"

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
    install_dir="${JEXTRACT_HOME:-$root_dir/.clodin/jextract/$jextract_version/$platform_os-$platform_arch}"

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

# The macOS jextract build ships a small launcher plus a bundled JDK. On
# some Macs that launcher fails with "cannot execute binary file". Run
# the jextract module with the system JDK instead, using the bundled
# libclang and the module image extracted from the bundled runtime.
prepare_system_java_jextract() {
    local jextract_binary jextract_root runtime_dir modules_dir
    local java_bin jimage_bin version major java_home mirror entry name
    jextract_binary="$1"
    jextract_root="$(cd "$(dirname "$jextract_binary")/.." && pwd)"
    runtime_dir="$jextract_root/runtime"
    modules_dir="$jextract_root/modules-extracted"
    mirror="$jextract_root/system-java-home"

    [ -f "$runtime_dir/lib/modules" ] || return 1
    [ -d "$runtime_dir/conf/jextract" ] || return 1

    if [ -n "${JEXTRACT_JAVA:-}" ]; then
        java_bin="$JEXTRACT_JAVA"
    elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        java_bin="$JAVA_HOME/bin/java"
    else
        java_bin="java"
    fi

    if [ -n "${JEXTRACT_JIMAGE:-}" ]; then
        jimage_bin="$JEXTRACT_JIMAGE"
    elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jimage" ]; then
        jimage_bin="$JAVA_HOME/bin/jimage"
    else
        jimage_bin="jimage"
    fi

    command -v "$java_bin" >/dev/null 2>&1 || return 1
    command -v "$jimage_bin" >/dev/null 2>&1 || return 1

    version="$("$java_bin" -version 2>&1 | awk -F'\"' '/version/ {print $2; exit}')"
    major="${version%%.*}"
    if [ "$major" = "1" ]; then
        major="${version#1.}"
        major="${major%%.*}"
    fi
    case "$major" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ "$major" -ge 23 ] || return 1

    java_home="$("$java_bin" -XshowSettings:properties -version 2>&1 | awk -F' = ' '/java.home =/ {print $2; exit}')"
    [ -n "$java_home" ] || java_home="${JAVA_HOME:-}"
    [ -n "$java_home" ] || return 1

    if [ "$force" = "1" ] || [ ! -f "$modules_dir/org.openjdk.jextract/module-info.class" ]; then
        rm -rf "$modules_dir"
        mkdir -p "$modules_dir"
        "$jimage_bin" extract --dir "$modules_dir" "$runtime_dir/lib/modules" >/dev/null
    fi

    # jextract finds its builtin headers through java.home/conf/jextract.
    # Mirror the system JDK and add that directory without touching the JDK.
    if [ "$force" = "1" ] || [ ! -e "$mirror/conf/jextract" ] || [ ! -e "$mirror/lib" ]; then
        rm -rf "$mirror"
        mkdir -p "$mirror/conf"
        for entry in "$java_home"/*; do
            [ -e "$entry" ] || continue
            name="$(basename "$entry")"
            [ "$name" = "conf" ] && continue
            ln -s "$entry" "$mirror/$name"
        done
        if [ -d "$java_home/conf" ]; then
            for entry in "$java_home/conf"/*; do
                [ -e "$entry" ] || continue
                name="$(basename "$entry")"
                [ "$name" = "jextract" ] && continue
                ln -s "$entry" "$mirror/conf/$name"
            done
        fi
        ln -s "$runtime_dir/conf/jextract" "$mirror/conf/jextract"
    fi

    jextract_java_bin="$java_bin"
    jextract_java_home="$mirror"
    jextract_java_library_path="$runtime_dir/lib"
    jextract_java_module_path="$modules_dir"
    return 0
}

if [ -n "${JEXTRACT_BIN:-}" ]; then
    [ -x "$JEXTRACT_BIN" ] || die "JEXTRACT_BIN is not executable: $JEXTRACT_BIN"
    jextract_bin="$JEXTRACT_BIN"
else
    jextract_bin="$(download_jextract)"
fi

if [ "${JEXTRACT_USE_BUNDLED_JAVA:-0}" = "1" ]; then
    jextract_command=("$jextract_bin")
elif [ "${JEXTRACT_USE_SYSTEM_JAVA:-0}" != "1" ] && "$jextract_bin" --version >/dev/null 2>&1; then
    jextract_command=("$jextract_bin")
elif prepare_system_java_jextract "$jextract_bin"; then
    jextract_command=(
        "$jextract_java_bin"
        "--enable-native-access=org.openjdk.jextract"
        "-Djava.library.path=$jextract_java_library_path"
        "-Djava.home=$jextract_java_home"
        "--module-path=$jextract_java_module_path"
        "-m" "org.openjdk.jextract/org.openjdk.jextract.JextractTool"
    )
else
    printf 'warning: could not prepare the system JDK for jextract; using the bundled runtime\n' >&2
    jextract_command=("$jextract_bin")
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

printf 'Using jextract: %s\n' "${jextract_command[*]}"
printf 'Using headers:  %s\n' "$llvm_include"
printf 'Using library:  %s\n' "$llvm_library"
printf 'Generating:     %s\n' "$jextract_output"

"${jextract_command[@]}" "${jextract_arguments[@]}"

java_files="$(find "$jextract_output" -type f -name '*.java' | wc -l | tr -d ' ')"
[ "$java_files" != "0" ] || die "jextract produced no Java files in $jextract_output"

printf 'Compiling %s Java files into %s\n' "$java_files" "$jextract_classes"
find "$jextract_output" -type f -name '*.java' -print0 | \
    xargs -0 javac -d "$jextract_classes"

jextract_classes_absolute="$(cd "$jextract_classes" && pwd)"
link_target="$jextract_classes_absolute"
case "$jextract_current:$jextract_classes" in
    "$root_dir/target/current:$root_dir/target/"*)
        link_target="${jextract_classes#$root_dir/target/}"
        ;;
esac
if [ -L "$jextract_current" ]; then
    rm -f "$jextract_current"
elif [ -e "$jextract_current" ]; then
    rm -rf "$jextract_current"
fi
mkdir -p "$(dirname "$jextract_current")"
ln -s "$link_target" "$jextract_current"

printf 'Platform:       %s\n' "$jextract_platform"
printf 'Active classes: %s -> %s\n' "$jextract_current" "$link_target"
printf 'Done.\n'
