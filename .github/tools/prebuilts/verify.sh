#!/bin/sh
# Checks that the prebuilt files in this repo are what docs/PROVENANCE.md says they are, by
# building them again from the pinned upstream source and comparing the bytes.
#
#   sh .github/tools/prebuilts/verify.sh [--exact] [--work DIR] [PART...]
#   sh .github/tools/prebuilts/verify.sh --apk Starwire.apk
#
# PART is any of these (default: all of them):
#   sums         the committed files match SHA256SUMS, and every binary file in git is listed
#   wrapper      gradle-wrapper.jar matches the checksum Gradle publishes (needs the network)
#   adrenotools  rebuild libadrenotools.so and its 4 hook libraries and compare them
#   pyrowave     rebuild libpyrowave-shared.so and compare it
#   shaders      regenerate app/src/main/jni/vulkan/shaders_spv.h and compare it
#
# --apk FILE   check the prebuilt libraries inside an APK against SHA256SUMS instead
# --exact      only accept files that are the same byte for byte (see "Results" below)
# --work DIR   where to put the sources and builds (default: $TMPDIR/starwire-prebuilts). It
#              is created if missing, and the script only ever deletes inside it. No spaces.
#
# Needs git, curl (for wrapper), Python 3, and for the rebuilds the exact NDKs below plus the
# SDK's CMake 3.31.6, installed with: sdkmanager "ndk;29.0.14206865" "ndk;27.0.12077973" "cmake;3.31.6"
# It finds them in ANDROID_HOME (or ANDROID_SDK_ROOT). These override that:
#   NDK29, NDK27   the two NDK folders
#   CMAKE, NINJA   cmake and ninja
#   PYTHON         python 3
# On Windows, run it from Git Bash.
#
# Results, per file:
#   MATCH                   the same, byte for byte
#   MATCH EXCEPT BUILD ID   only the 20-byte build ID differs (it hashes the build folder's path)
#   SAME CODE               everything Android loads is the same; only the build ID and parts
#                           the loader never reads (like the compiler's name) differ
#   DIFFERENT               the code or data differ
# Exit status: 0 when every file checked passes, 1 when something differs, 2 when something
# couldn't be checked (a missing tool, NDK or network).
#
# This script never changes the repo.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)

# --- Pins. Change these only together with the files they produce. ---------------------------

# libadrenotools, built for Starwire on Windows with NDK r29 by build_libadrenotools.sh. The
# hook libraries' build ID hashes the folders that build ran in (read back from its debug info),
# so the rebuild maps its own folders to those with -ffile-prefix-map and runs in
# <checkout>/build-android-arm64 as the script did.
ADRENOTOOLS_URL=https://github.com/bylaws/libadrenotools
ADRENOTOOLS_COMMIT=8fae8ce254dfc1344527e05301e43f37dea2df80
LINKERNSBYPASS_COMMIT=aa3975893d83ef1bc84c321ec60c65fbf1287887
ADRENOTOOLS_NDK_VERSION=29.0.14206865
ADRENOTOOLS_ORIGINAL_SRC=D:/REA/AndroidApps/adrenotools
ADRENOTOOLS_ORIGINAL_NDK=C:/Android/Sdk/ndk/29.0.14206865
ADRENOTOOLS_ORIGINAL_HOST=windows-x86_64

# PyroWave, built by Moonlight X with NDK r27 (its .note.android.ident says r27 12077973).
# The folders that build used aren't known, so only MATCH EXCEPT BUILD ID or SAME CODE is
# possible for it.
PYROWAVE_URL=https://github.com/Themaister/pyrowave
PYROWAVE_COMMIT=89f7e47d4abbf650c91fae766728af866c5e32a0
GRANITE_URL=https://github.com/Themaister/Granite
GRANITE_COMMIT=1b2d1801d2910fb09ebcded2f0bb3a3a781103b5
VOLK_COMMIT=47cddf7ed97b94118a08aacb548a411188e016cc
VULKAN_HEADERS_COMMIT=6802bb4733b63ed5efd3adb308a6c885ef180ea1
PYROWAVE_NDK_VERSION=27.0.12077973

# shaders_spv.h is made with the glslc in this NDK
SHADERS_NDK_VERSION=29.0.14206865

# gradle-wrapper.jar is the wrapper jar from this Gradle release (7.4 and 7.4.1 ship the same one)
WRAPPER_GRADLE_VERSION=7.4.2

CMAKE_VERSION=3.31.6

ADRENOTOOLS_LIBS="libadrenotools libhook_impl libmain_hook libfile_redirect_hook libgsl_alloc_hook"
ADRENOTOOLS_DIR=app/src/main/jni/adrenotools/lib/arm64-v8a
PYROWAVE_DIR=app/src/main/jni/pyrowave/lib/arm64-v8a
SUMS=$HERE/SHA256SUMS

# --- Arguments --------------------------------------------------------------------------------

EXACT=0
APK=
WORK=${TMPDIR:-/tmp}/starwire-prebuilts
PARTS=
while [ $# -gt 0 ]; do
    case $1 in
        --exact) EXACT=1 ;;
        --work) WORK=$2; shift ;;
        --apk) APK=$2; shift ;;
        sums|wrapper|adrenotools|pyrowave|shaders) PARTS="$PARTS $1" ;;
        -h|--help) sed -n '2,37p' "$0"; exit 0 ;;
        *) echo "Unknown argument: $1 (try --help)" >&2; exit 2 ;;
    esac
    shift
done
[ -n "$PARTS" ] || PARTS="sums wrapper adrenotools pyrowave shaders"

# --- Helpers ----------------------------------------------------------------------------------

STATUS=0
SUMMARY=

# On Git Bash, turn /c/x paths into C:/x, which Windows programs and Git Bash both understand
native() {
    if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else printf '%s\n' "$1"; fi
}

note() { printf '%s\n' "$*" >&2; }

# A result line, kept for the summary at the end
result() {  # result LABEL NAME DETAIL
    line=$(printf '%-22s %-26s %s' "$1" "$2" "$3")
    printf '%s\n' "$line"
    SUMMARY="$SUMMARY$line
"
}

cannot() {  # cannot NAME REASON: something couldn't be checked
    result "NOT CHECKED" "$1" "$2"
    [ "$STATUS" -ne 0 ] || STATUS=2
}

failed() { STATUS=1; }

sha256() {
    if command -v sha256sum > /dev/null 2>&1; then
        sha256sum "$1" | cut -c1-64
    else
        shasum -a 256 "$1" | cut -c1-64
    fi
}

# The SHA-256 SHA256SUMS records for a path in the repo
recorded() {
    tr -d '\r' < "$SUMS" | awk -v p="$1" '$2 == p || $2 == "*" p { print $1; exit }'
}

# Prints the path of every binary file in git. A file is binary when it has a NUL byte, isn't
# UTF-8, or more than 1 byte in 128 is a control character other than a tab, a line ending, a
# form feed, a backspace or an escape. The last rule is git's own, but git also calls text with a
# lone CR binary, so it can't be asked: real tool output like fixtures/sdkmanager-list-22.txt mixes
# CR, CRLF and LF line endings. It reads what git has, not the checkout.
binary_files() {
    "$PY" -I -c '
import subprocess, sys
repo = sys.argv[1]

def git(args, given=None):
    return subprocess.run(["git", "-C", repo] + args, input=given, stdout=subprocess.PIPE, check=True).stdout

files = []
for entry in git(["ls-files", "-s", "-z"]).split(b"\0"):
    if entry:
        meta, path = entry.split(b"\t", 1)
        mode, blob = meta.split(b" ")[:2]
        if mode in (b"100644", b"100755"):  # not a submodule or a symbolic link
            files.append((blob, path))
contents = git(["cat-file", "--batch"], b"".join(blob + b"\n" for blob, _ in files))
control = bytes(c for c in range(32) if c not in b"\t\n\r\f\b\x1b") + b"\x7f"
at = 0
for blob, path in files:
    eol = contents.index(b"\n", at)
    size = int(contents[at:eol].split()[2])
    data = contents[eol + 1:eol + 1 + size]
    at = eol + 2 + size
    if b"\0" in data:
        binary = True
    else:
        try:
            data.decode("utf-8")
        except UnicodeDecodeError:
            binary = True
        else:
            if data.endswith(b"\x1a"):  # a DOS end-of-file mark, which git ignores too
                data = data[:-1]
            odd = len(data) - len(data.translate(None, control))
            printable = len(data) - odd - data.count(b"\r") - data.count(b"\n")
            binary = (printable >> 7) < odd
    if binary:
        sys.stdout.buffer.write(path + b"\n")
' "$(native "$REPO")"
}

find_python() {
    for p in "${PYTHON:-}" python3 python py; do
        [ -n "$p" ] || continue
        if "$p" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 6) else 1)' > /dev/null 2>&1; then
            PY=$p
            return 0
        fi
    done
    return 1
}

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$SDK" ]; then
    for d in "${LOCALAPPDATA:-}/Android/Sdk" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" C:/Android/Sdk; do
        if [ -d "$d" ]; then SDK=$d; break; fi
    done
fi
[ -z "$SDK" ] || SDK=$(native "$SDK")

ndk_dir() {  # ndk_dir VERSION [OVERRIDE]
    if [ -n "${2:-}" ]; then
        note "Using $2 in place of NDK $1"
        native "$2"
    else
        printf '%s\n' "$SDK/ndk/$1"
    fi
}

host_tag() {  # host_tag NDK: linux-x86_64, windows-x86_64 or darwin-x86_64
    ls "$1/toolchains/llvm/prebuilt" | head -n 1
}

find_cmake() {
    CMAKE=${CMAKE:-$SDK/cmake/$CMAKE_VERSION/bin/cmake}
    NINJA=${NINJA:-$SDK/cmake/$CMAKE_VERSION/bin/ninja}
    for t in "$CMAKE" "$NINJA"; do
        [ -x "$t" ] || [ -x "$t.exe" ] || { note "Not found: $t. Install it with: sdkmanager \"cmake;$CMAKE_VERSION\", or set CMAKE and NINJA"; return 1; }
    done
    if ! "$CMAKE" --version | head -n 1 | tr -d '\r' | grep -q " $CMAKE_VERSION\$"; then
        note "Note: $CMAKE is not CMake $CMAKE_VERSION, which the files were built with"
    fi
    CMAKE=$(native "$CMAKE")
    NINJA=$(native "$NINJA")
}

# A shallow checkout of exactly one commit
fetch() {  # fetch DIR URL COMMIT
    rm -rf "$1"
    mkdir -p "$1"
    git init -q "$1"
    if ! git -C "$1" -c core.longpaths=true fetch -q --depth 1 "$2" "$3"; then
        note "Could not fetch $3 from $2"
        return 1
    fi
    git -C "$1" -c core.longpaths=true -c core.autocrlf=false -c advice.detachedHead=false checkout -q FETCH_HEAD
    got=$(git -C "$1" rev-parse HEAD)
    [ "$got" = "$3" ] || { note "$2 gave $got, not $3"; return 1; }
}

# Check that a parent repo pins a submodule at the expected commit, then fetch that commit
submodule() {  # submodule PARENT PATH COMMIT
    pinned=$(git -C "$1" ls-tree HEAD "$2" | awk '$2 == "commit" { print $3 }')
    [ "$pinned" = "$3" ] || { note "$1 pins $2 at '$pinned', not $3"; return 1; }
    name=$(git -C "$1" config -f .gitmodules --get-regexp '^submodule\..*\.path$' |
        awk -v p="$2" '$2 == p { sub(/^submodule\./, "", $1); sub(/\.path$/, "", $1); print $1 }')
    url=$(git -C "$1" config -f .gitmodules --get "submodule.$name.url")
    fetch "$1/$2" "$url" "$3"
}

cmake_build() {  # cmake_build SRC BUILD NDK LOG TARGET [cmake args...]
    src=$1; build=$2; ndk=$3; log=$4; target=$5; shift 5
    rm -rf "$build"
    # The paths are already in Windows form, so stop Git Bash from rewriting them
    if ! MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$CMAKE" -S "$src" -B "$build" -G Ninja \
            -DCMAKE_MAKE_PROGRAM="$NINJA" \
            -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
            -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
            -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
            -DCMAKE_BUILD_TYPE=Release "$@" > "$log" 2>&1 ||
       ! "$NINJA" -C "$build" $target >> "$log" 2>&1; then
        tail -n 30 "$log" >&2
        note "The build failed; the whole log is $log"
        return 1
    fi
}

# Compare a header Starwire keeps a copy of with the upstream one, ignoring line endings
same_text() {  # same_text NAME UPSTREAM COPY
    tr -d '\r' < "$2" > "$WORK/upstream.tmp"
    tr -d '\r' < "$REPO/$3" > "$WORK/copy.tmp"
    if cmp -s "$WORK/upstream.tmp" "$WORK/copy.tmp"; then
        result "MATCH" "$1" "same as upstream"
    else
        result "DIFFERENT" "$1" "$3 isn't the upstream file"
        failed
    fi
}

# Compare a rebuilt file with the committed one and print the result
judge() {  # judge NAME COMMITTED REBUILT
    want=$(recorded "$2")
    [ -n "$want" ] || { result "DIFFERENT" "$1" "not listed in SHA256SUMS"; failed; return; }
    if [ "$(sha256 "$REPO/$2")" != "$want" ]; then
        result "DIFFERENT" "$1" "the committed file doesn't match SHA256SUMS"
        failed
        return
    fi
    got=$(sha256 "$3")
    if [ "$got" = "$want" ]; then
        result "MATCH" "$1" "$got"
        return
    fi
    level=$("$PY" -I "$(native "$HERE/elf_compare.py")" "$(native "$REPO/$2")" "$(native "$3")" | cut -d' ' -f1) || true
    case $level in
        build-id)
            result "MATCH EXCEPT BUILD ID" "$1" "rebuilt $got"
            [ "$EXACT" = 0 ] || failed ;;
        code)
            result "SAME CODE" "$1" "rebuilt $got"
            [ "$EXACT" = 0 ] || failed ;;
        *)
            result "DIFFERENT" "$1" "rebuilt $got, committed $want"
            failed ;;
    esac
}

prepare_work() {
    WORK=$(native "$WORK")
    case $WORK in *" "*) note "The work folder can't contain spaces: $WORK"; exit 2 ;; esac
    if [ -e "$WORK" ] && [ ! -e "$WORK/.starwire-prebuilts" ] && [ -n "$(ls -A "$WORK" 2>/dev/null)" ]; then
        note "$WORK isn't empty and wasn't made by this script; pick another folder with --work"
        exit 2
    fi
    mkdir -p "$WORK/out" "$WORK/logs"
    : > "$WORK/.starwire-prebuilts"
    WORK=$(native "$(cd "$WORK" && pwd)")
}

# --- Parts ------------------------------------------------------------------------------------

check_sums() {
    note "== Committed files against SHA256SUMS"
    tr -d '\r' < "$SUMS" | while read -r hash path; do
        path=${path#\*}
        if [ ! -f "$REPO/$path" ]; then
            echo "MISSING $path"
        elif [ "$(sha256 "$REPO/$path")" != "$hash" ]; then
            echo "CHANGED $path"
        fi
    done > "$WORK/logs/sums.txt"
    scanned=yes
    if git -C "$REPO" rev-parse --git-dir > /dev/null 2>&1; then
        # Every binary file in git must be listed, apart from images
        if binary_files > "$WORK/logs/binary-files.txt"; then
            grep -viE '\.(png|jpe?g|gif|webp|ico|svg)$' "$WORK/logs/binary-files.txt" | while read -r path; do
                [ -n "$(recorded "$path")" ] || echo "UNLISTED $path"
            done >> "$WORK/logs/sums.txt"
        else
            scanned=no
        fi
    fi
    if [ -s "$WORK/logs/sums.txt" ]; then
        while read -r what path; do
            case $what in
                MISSING) result "DIFFERENT" "$path" "listed in SHA256SUMS but missing" ;;
                CHANGED) result "DIFFERENT" "$path" "doesn't match SHA256SUMS" ;;
                UNLISTED) result "DIFFERENT" "$path" "binary file not listed in SHA256SUMS" ;;
            esac
        done < "$WORK/logs/sums.txt"
        failed
    else
        result "MATCH" "SHA256SUMS" "$(tr -d '\r' < "$SUMS" | grep -c .) files"
    fi
    [ "$scanned" = yes ] || cannot "binary files in git" "couldn't read the files in git to find them"
}

check_wrapper() {
    note "== gradle-wrapper.jar against Gradle's published checksum"
    url=https://services.gradle.org/distributions/gradle-$WRAPPER_GRADLE_VERSION-wrapper.jar.sha256
    if ! published=$(curl -fsSL "$url" | tr -d '\r\n '); then
        cannot "gradle-wrapper.jar" "couldn't download $url"
        return
    fi
    got=$(sha256 "$REPO/gradle/wrapper/gradle-wrapper.jar")
    if [ "$got" = "$published" ]; then
        result "MATCH" "gradle-wrapper.jar" "Gradle $WRAPPER_GRADLE_VERSION, $got"
    else
        result "DIFFERENT" "gradle-wrapper.jar" "$got, Gradle publishes $published"
        failed
    fi
}

check_adrenotools() {
    note "== libadrenotools $ADRENOTOOLS_COMMIT with NDK $ADRENOTOOLS_NDK_VERSION"
    ndk=$(ndk_dir "$ADRENOTOOLS_NDK_VERSION" "${NDK29:-}")
    if [ ! -d "$ndk" ]; then cannot "libadrenotools" "NDK $ADRENOTOOLS_NDK_VERSION not found at $ndk"; return; fi
    find_cmake || { cannot "libadrenotools" "CMake $CMAKE_VERSION not found"; return; }
    src=$WORK/src/adrenotools
    # The original build ran in <checkout>/build-android-arm64, so this does too
    build=$src/build-android-arm64
    log=$WORK/logs/adrenotools.log
    if ! fetch "$src" "$ADRENOTOOLS_URL" "$ADRENOTOOLS_COMMIT" ||
       ! submodule "$src" lib/linkernsbypass "$LINKERNSBYPASS_COMMIT"; then
        cannot "libadrenotools" "couldn't fetch the pinned source"
        return
    fi
    host=$(host_tag "$ndk")
    # Later maps win, so the NDK's toolchain folder comes after the NDK itself
    maps="-ffile-prefix-map=$src=$ADRENOTOOLS_ORIGINAL_SRC"
    maps="$maps -ffile-prefix-map=$ndk=$ADRENOTOOLS_ORIGINAL_NDK"
    maps="$maps -ffile-prefix-map=$ndk/toolchains/llvm/prebuilt/$host=$ADRENOTOOLS_ORIGINAL_NDK/toolchains/llvm/prebuilt/$ADRENOTOOLS_ORIGINAL_HOST"
    if ! cmake_build "$src" "$build" "$ndk" "$log" "" -DBUILD_SHARED_LIBS=ON \
            -DCMAKE_C_FLAGS="$maps" -DCMAKE_CXX_FLAGS="$maps"; then
        cannot "libadrenotools" "the build failed"
        return
    fi
    strip=$(ls "$ndk/toolchains/llvm/prebuilt/$host/bin/"llvm-strip* | head -n 1)
    for name in $ADRENOTOOLS_LIBS; do
        case $name in
            libadrenotools) built=$build/$name.so ;;
            *) built=$build/src/hook/$name.so ;;
        esac
        "$strip" --strip-unneeded -o "$WORK/out/$name.so" "$built"
        judge "$name.so" "$ADRENOTOOLS_DIR/$name.so" "$WORK/out/$name.so"
    done
    for h in driver.h priv.h; do
        same_text "$h" "$src/include/adrenotools/$h" "app/src/main/jni/adrenotools/include/adrenotools/$h"
    done
}

check_pyrowave() {
    note "== PyroWave $PYROWAVE_COMMIT with NDK $PYROWAVE_NDK_VERSION"
    ndk=$(ndk_dir "$PYROWAVE_NDK_VERSION" "${NDK27:-}")
    if [ ! -d "$ndk" ]; then cannot "libpyrowave-shared.so" "NDK $PYROWAVE_NDK_VERSION not found at $ndk"; return; fi
    find_cmake || { cannot "libpyrowave-shared.so" "CMake $CMAKE_VERSION not found"; return; }
    src=$WORK/src/pyrowave
    build=$src/build-android-arm64
    log=$WORK/logs/pyrowave.log
    if ! fetch "$src" "$PYROWAVE_URL" "$PYROWAVE_COMMIT"; then
        cannot "libpyrowave-shared.so" "couldn't fetch the pinned source"
        return
    fi
    # PyroWave pins Granite in checkout_granite.sh rather than as a submodule
    if ! grep -q "^GRANITE_COMMIT=$GRANITE_COMMIT\$" "$src/checkout_granite.sh"; then
        result "DIFFERENT" "libpyrowave-shared.so" "checkout_granite.sh pins another Granite than $GRANITE_COMMIT"
        failed
        return
    fi
    if ! fetch "$src/Granite" "$GRANITE_URL" "$GRANITE_COMMIT" ||
       ! submodule "$src/Granite" third_party/volk "$VOLK_COMMIT" ||
       ! submodule "$src/Granite" third_party/khronos/vulkan-headers "$VULKAN_HEADERS_COMMIT"; then
        cannot "libpyrowave-shared.so" "couldn't fetch the pinned source"
        return
    fi
    if ! cmake_build "$src" "$build" "$ndk" "$log" pyrowave-shared; then
        cannot "libpyrowave-shared.so" "the build failed"
        return
    fi
    host=$(host_tag "$ndk")
    strip=$(ls "$ndk/toolchains/llvm/prebuilt/$host/bin/"llvm-strip* | head -n 1)
    "$strip" --strip-unneeded -o "$WORK/out/libpyrowave-shared.so" "$build/libpyrowave-shared.so"
    judge "libpyrowave-shared.so" "$PYROWAVE_DIR/libpyrowave-shared.so" "$WORK/out/libpyrowave-shared.so"
    same_text "pyrowave.h" "$src/pyrowave.h" "app/src/main/jni/pyrowave/pyrowave.h"
}

check_shaders() {
    note "== shaders_spv.h with the glslc in NDK $SHADERS_NDK_VERSION"
    ndk=$(ndk_dir "$SHADERS_NDK_VERSION" "${NDK29:-}")
    if [ ! -d "$ndk" ]; then cannot "shaders_spv.h" "NDK $SHADERS_NDK_VERSION not found at $ndk"; return; fi
    glslc=$(ls "$ndk/shader-tools/"*/glslc* | head -n 1)
    # Work on a copy, in git's line endings, so the repo is never touched
    dir=$WORK/shaders
    rm -rf "$dir"
    mkdir -p "$dir/shaders"
    for f in "$REPO"/app/src/main/jni/vulkan/shaders/*; do
        tr -d '\r' < "$f" > "$dir/shaders/$(basename "$f")"
    done
    if ! GLSLC=$glslc sh "$dir/shaders/compile_shaders.sh" > "$WORK/logs/shaders.log" 2>&1; then
        cat "$WORK/logs/shaders.log" >&2
        cannot "shaders_spv.h" "compile_shaders.sh failed"
        return
    fi
    tr -d '\r' < "$REPO/app/src/main/jni/vulkan/shaders_spv.h" > "$dir/committed.h"
    tr -d '\r' < "$dir/shaders_spv.h" > "$dir/rebuilt.h"
    if cmp -s "$dir/committed.h" "$dir/rebuilt.h"; then
        result "MATCH" "shaders_spv.h" "$(sha256 "$dir/rebuilt.h") (with LF line endings)"
    else
        result "DIFFERENT" "shaders_spv.h" "regenerated file is $dir/shaders_spv.h"
        failed
    fi
}

check_apk() {
    note "== Prebuilt libraries inside $APK"
    [ -f "$APK" ] || { note "No such file: $APK"; exit 2; }
    find_python || { note "Python 3 is needed; set PYTHON"; exit 2; }
    names=
    for name in $ADRENOTOOLS_LIBS libpyrowave-shared; do names="$names lib/arm64-v8a/$name.so"; done
    # shellcheck disable=SC2086
    "$PY" -I -c '
import hashlib, sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
present = set(z.namelist())
for n in sys.argv[2:]:
    print((hashlib.sha256(z.read(n)).hexdigest() if n in present else "missing"), n)
' "$(native "$APK")" $names | tr -d '\r' | while read -r got entry; do
        name=${entry#lib/arm64-v8a/}
        case $name in
            libpyrowave-shared.so) path=$PYROWAVE_DIR/$name ;;
            *) path=$ADRENOTOOLS_DIR/$name ;;
        esac
        want=$(recorded "$path")
        if [ "$got" = "$want" ]; then
            echo "MATCH $name $got"
        else
            echo "DIFFERENT $name $got"
        fi
    done > "$WORK/logs/apk.txt"
    while read -r what name got; do
        result "$what" "$name" "$got"
        [ "$what" = MATCH ] || failed
    done < "$WORK/logs/apk.txt"
}

# --- Run --------------------------------------------------------------------------------------

prepare_work

if [ -n "$APK" ]; then
    check_apk
else
    case $PARTS in *sums*|*adrenotools*|*pyrowave*)
        find_python || { note "Python 3 is needed to find binary files and compare libraries; set PYTHON"; exit 2; } ;;
    esac
    for part in $PARTS; do
        case $part in
            sums) check_sums ;;
            wrapper) check_wrapper ;;
            adrenotools) check_adrenotools ;;
            pyrowave) check_pyrowave ;;
            shaders) check_shaders ;;
        esac
    done
fi

echo
echo "Summary:"
printf '%s' "$SUMMARY"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
        echo '```'
        printf '%s' "$SUMMARY"
        echo '```'
    } >> "$GITHUB_STEP_SUMMARY"
fi
exit $STATUS
