#!/usr/bin/env bash
# Runs Starwire's on-device tests over adb, on real devices and emulators, one after another, and
# says how each went.
#
#   .github/tools/device-test.sh -s SERIAL [-s SERIAL ...] [options]
#
#   -s SERIAL         a device to test (adb devices -l lists them). Give it more than once for
#                     several. Without -s it takes the serials in STARWIRE_TEST_DEVICES
#                     (separated by spaces); it never picks whatever happens to be connected.
#   --apk-dir DIR     where the built APKs are (default app/build/outputs/apk). Build them with
#                     gradlew assembleNonRootDebug assembleNonRootDebugAndroidTest
#   --out DIR         where the results go (default build/device-tests/<date and time>)
#   --no-install      test what is installed already
#   --smoke-only      skip the instrumented suite, run only the smoke tests
#   --only CLASSES    the instrumented suite runs only these test classes, separated by commas
#                     (the emulator matrix reruns the layout tests this way after each change)
#   --allow-stream    also run the stream smoke test: it starts Desktop on the paired PC, drags
#                     the menu button, opens and closes the in-stream menu, and disconnects. It
#                     never quits the session and never starts another app. Without this flag
#                     nothing is streamed.
#   --monkey N        afterwards, N random taps and keys (adb shell monkey) with a fixed seed. Only
#                     on a device where the app knows no PC at all, which MonkeyPreconditionTest
#                     checks first; an emulator is also put in airplane mode for it. A real device
#                     also needs --monkey-real-device, because random input there can reach the
#                     system's quick settings.
#   --monkey-real-device
#
# For each device, in order: install the debug app and then its tests with adb install -r (it
# never uninstalls anything: that would lose the pairing with the PC), run the instrumented
# suite, run the smoke tests (UI Automator: the PC list, every settings section, screens changing
# size and being recreated, and the paired PC's games), pull their screenshots, check the Hide PC
# cursor setting is what it was, then look for crashes and ANRs from the app since the start.
# Smoke tests that need the PC skip, and say why, when there's no paired PC or it isn't online.
# On an emulator, tests marked @HardwareOnly (com.limelight.testing) are left out. Every test runs
# under a watchdog (com.limelight.testing.StuckTestWatchdog): one that hangs for 90 seconds (a
# smoke test, which may wait for the PC and a stream: 5 minutes) fails with what the app's main
# thread was doing, and the run goes on to the next test.
#
# Exit codes: 0 all good; 1 a test failed or the cursor setting changed; 2 a crash, an ANR, or a
# test run that didn't finish; 3 an install failed; 4 a device problem (not connected, locked, a
# stream already open, or refused) or bad arguments. With several devices the worst one counts,
# in that order: 2, then 1, 3 and 4.

set -uo pipefail
# Git Bash: hand /sdcard/... to adb untouched. Local paths go through host_path instead.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
PKG="${STARWIRE_PKG:-com.starwire.debug}"
TEST_PKG="$PKG.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
SEED=20261023
SUITE_TIMEOUT=2400
SMOKE_TIMEOUT=900

# Devices refused before anything is sent to them, like a phone kept for other work. List them
# in private/deny-devices.txt (git-ignored, so the IDs stay on your PC), or in the file named by
# STARWIRE_DENY_FILE, one per line, or in STARWIRE_DENY_DEVICES, space separated: serials, models
# or sha256:<hash of one>. Lines starting with # are comments.
DENY_FILE=${STARWIRE_DENY_FILE:-$(cd "$(dirname "$0")/../.." && pwd)/private/deny-devices.txt}
DENY_DEVICES=""
if [ -f "$DENY_FILE" ]; then
    DENY_DEVICES=$(tr -d '\r' < "$DENY_FILE" | sed 's/#.*//' | tr -s ' \t\n' ' ')
fi

usage() {
    sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-4}"
}

serials=()
apk_dir="$ROOT/app/build/outputs/apk"
out=""
install=1
suite=1
only=""
allow_stream=false
monkey=0
monkey_real=0
while [ $# -gt 0 ]; do
    case "$1" in
        -s) [ $# -ge 2 ] || usage; serials+=("$2"); shift 2 ;;
        --apk-dir) [ $# -ge 2 ] || usage; apk_dir="$2"; shift 2 ;;
        --out) [ $# -ge 2 ] || usage; out="$2"; shift 2 ;;
        --no-install) install=0; shift ;;
        --smoke-only) suite=0; shift ;;
        --only) [[ "${2:-}" =~ ^[A-Za-z0-9_.$,#]+$ ]] || usage; only="$2"; shift 2 ;;
        --allow-stream) allow_stream=true; shift ;;
        --monkey) [[ "${2:-}" =~ ^[0-9]+$ ]] || usage; monkey="$2"; shift 2 ;;
        --monkey-real-device) monkey_real=1; shift ;;
        -h|--help) usage 0 ;;
        *) echo "unknown option: $1" >&2; usage ;;
    esac
done
if [ ${#serials[@]} -eq 0 ] && [ -n "${STARWIRE_TEST_DEVICES:-}" ]; then
    read -r -a serials <<< "$STARWIRE_TEST_DEVICES"
fi
[ ${#serials[@]} -gt 0 ] || usage

# The SDK's adb, the one the build uses: two different adb versions on one PC restart each
# other's server, which drops wireless connections
find_adb() {
    if [ -n "${ADB:-}" ]; then echo "$ADB"; return; fi
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -z "$sdk" ] && [ -f "$ROOT/local.properties" ]; then
        sdk=$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | tr -d '\r' | sed 's/\\:/:/g; s/\\\\/\//g' | tail -n 1)
    fi
    if [ -n "$sdk" ]; then
        for candidate in "$sdk/platform-tools/adb" "$sdk/platform-tools/adb.exe"; do
            if [ -x "$candidate" ]; then echo "$candidate"; return; fi
        done
    fi
    command -v adb || echo adb
}
ADB="$(find_adb)"

# adb on Windows wants C:/... rather than Git Bash's /c/...
host_path() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s\n' "$1"; fi
}

sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        printf '%s' "$1" | sha256sum | cut -d' ' -f1
    else
        printf '%s' "$1" | shasum -a 256 | cut -d' ' -f1
    fi
}

# Whether a device is refused. Decided from its serial and from adb's own list, without
# sending the device anything.
refused() {
    local serial=$1 model id hash deny
    local ids=("$serial")
    # Wireless debugging names a device adb-<serial>-<random>._adb-tls-connect._tcp
    if [[ "$serial" =~ ^adb-([^-]+)- ]]; then ids+=("${BASH_REMATCH[1]}"); fi
    model=$("$ADB" devices -l 2>/dev/null | tr -d '\r' | awk -v s="$serial" '$1 == s' |
        sed -n 's/.* model:\([^ ]*\).*/\1/p')
    if [ -n "$model" ]; then ids+=("$model"); fi
    for id in "${ids[@]}"; do
        hash=$(sha256 "$id")
        for deny in $DENY_DEVICES ${STARWIRE_DENY_DEVICES:-}; do
            if [ "$id" = "$deny" ] || [ "sha256:$hash" = "$deny" ]; then return 0; fi
        done
    done
    return 1
}

adb_() {
    "$ADB" -s "$SERIAL" "$@"
}
sh_() {
    "$ADB" -s "$SERIAL" shell "$@" | tr -d '\r'
}
note() {
    echo "$*" | tee -a "$DIR/notes.txt"
}

# The app's Hide PC cursor setting, and how many PCs Starwire thinks it left the cursor hidden
# on (HostCursorLedger). Only a debuggable app can be read this way.
cursor_setting() {
    local prefs mode
    prefs=$(adb_ exec-out run-as "$PKG" cat "shared_prefs/${PKG}_preferences.xml" 2>/dev/null)
    case "$prefs" in
        *"<map"*) ;;
        *) echo "unknown (the app's settings can't be read)"; return ;;
    esac
    mode=$(printf '%s\n' "$prefs" | sed -n 's/.*name="hide_cursor_mode">\([^<]*\)<.*/\1/p')
    echo "${mode:-never (the default)}"
}
cursor_left_hidden() {
    local listing
    listing=$(adb_ exec-out "run-as $PKG sh -c 'ls no_backup/host_cursor 2>/dev/null; echo end'" 2>/dev/null | tr -d '\r')
    case "$listing" in
        *end) printf '%s\n' "$listing" | grep -cE '^[0-9a-f]+$' ;;
        *) echo "?" ;;
    esac
}

# Runs am instrument with the given arguments, keeps its output as NAME.txt and the verdict as
# NAME-results.txt, and returns parse-instrument.sh's exit code
run_instrument() {
    local name=$1 limit=$2 status
    shift 2
    if command -v timeout >/dev/null 2>&1; then
        timeout "$limit" "$ADB" -s "$SERIAL" shell am instrument -w -r "$@" "$TEST_PKG/$RUNNER" |
            tr -d '\r' > "$DIR/$name.txt"
        status=${PIPESTATUS[0]}
    else
        adb_ shell am instrument -w -r "$@" "$TEST_PKG/$RUNNER" | tr -d '\r' > "$DIR/$name.txt"
        status=0
    fi
    if [ "$status" = 124 ]; then
        # Stop the tests still running on the device
        echo "INSTRUMENTATION_RESULT: shortMsg=timed out after $limit s (device-test.sh)" >> "$DIR/$name.txt"
        adb_ shell am force-stop "$PKG" > /dev/null 2>&1
    fi
    bash "$HERE/parse-instrument.sh" "$DIR/$name.txt" > "$DIR/$name-results.txt"
}

# Tests one device into DIR. Returns its exit code.
test_device() {
    SERIAL=$1
    DIR=$2
    mkdir -p "$DIR"
    : > "$DIR/notes.txt"
    local failed=0 crashed=0

    if refused "$SERIAL"; then
        note "Refused: this device is on the list of devices not to test"
        return 4
    fi
    if [ "$("$ADB" -s "$SERIAL" get-state 2>/dev/null | tr -d '\r')" != device ]; then
        note "Not connected (adb devices -l lists what is)"
        return 4
    fi

    local model sdk abi size density emulator=no
    model=$(sh_ getprop ro.product.model)
    sdk=$(sh_ getprop ro.build.version.sdk)
    abi=$(sh_ getprop ro.product.cpu.abi)
    size=$(sh_ wm size | tail -n 1 | awk '{print $NF}')
    density=$(sh_ wm density | tail -n 1 | awk '{print $NF}')
    if [ "$(sh_ getprop ro.boot.qemu)" = 1 ] || [ "$(sh_ getprop ro.kernel.qemu)" = 1 ]; then
        emulator=yes
    fi
    echo "$model, Android API $sdk, $abi, $size at $density dpi, emulator: $emulator" > "$DIR/device.txt"
    echo "Device: $(cat "$DIR/device.txt")"
    # Tests that need real hardware and can't check for it themselves. (A test APK without the
    # annotation class runs everything: the runner only logs that it can't find it.)
    local skip=()
    if [ "$emulator" = yes ]; then skip=(-e notAnnotation com.limelight.testing.HardwareOnly); fi
    local watch=(-e listener com.limelight.testing.StuckTestWatchdog)

    # The smoke tests need the screen on and unlocked; this never wakes or unlocks it. (Each
    # dumpsys is read whole first: grep -q stopping early would fail the pipe under pipefail.)
    local state
    state=$(sh_ dumpsys power)
    if ! grep -q 'mWakefulness=Awake' <<< "$state"; then
        note "The screen is off: wake and unlock the device, then run this again"
        return 4
    fi
    state=$(sh_ dumpsys window)
    if grep -qE 'isKeyguardShowing=true|mShowingLockscreen=true|mDreamingLockscreen=true' <<< "$state"; then
        note "The device is locked: unlock it, then run this again"
        return 4
    fi
    # The tests bring the debug app to the front, which would end a stream in any Starwire
    state=$(sh_ dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity:')
    if grep -q '/com\.limelight\.Game' <<< "$state"; then
        note "A stream is open on this device: not interrupting it"
        return 4
    fi

    # 1. Install over what's there
    if [ "$install" = 1 ]; then
        local apk result
        for apk in "$apk_dir/nonRoot/debug/app-nonRoot-debug.apk" \
                   "$apk_dir/androidTest/nonRoot/debug/app-nonRoot-debug-androidTest.apk"; do
            if [ ! -f "$apk" ]; then
                note "Missing $apk: build it with gradlew assembleNonRootDebug assembleNonRootDebugAndroidTest"
                return 3
            fi
            result=$(adb_ install -r -t "$(host_path "$apk")" 2>&1 | tr -d '\r')
            printf '%s\n' "$result" >> "$DIR/install.txt"
            if ! grep -q '^Success' <<< "$result"; then
                # A differently signed APK (a CI build over a local one) only goes on after an
                # uninstall, which would delete the pairing and settings, so stop here
                note "Installing $(basename "$apk") failed, and this script doesn't uninstall: $(printf '%s\n' "$result" | tail -n 1)"
                return 3
            fi
        done
    fi

    # Only crashes from here on count
    local start_log start_box
    # (adb shell joins its arguments with spaces, so the format is quoted for the device's shell)
    start_log=$(sh_ "date '+%m-%d %H:%M:%S.000'")
    start_box=$(sh_ "date '+%Y-%m-%d %H:%M:%S'")

    # 2. The instrumented suite (everything but the smoke tests and the monkey check), or the
    # classes --only names
    local verdict which=(-e notPackage com.limelight.smoke,com.limelight.devicecheck)
    if [ -n "$only" ]; then which=(-e class "$only"); fi
    if [ "$suite" = 1 ]; then
        run_instrument suite "$SUITE_TIMEOUT" "${which[@]}" ${skip[@]+"${skip[@]}"} "${watch[@]}"
        verdict=$?
        [ $verdict = 1 ] && failed=1
        [ $verdict = 2 ] && crashed=1
    fi

    # 3. The smoke tests, and what they did to the Hide PC cursor setting
    local cursor_before cursor_after hidden_before hidden_after
    adb_ shell rm -rf "/sdcard/Android/data/$PKG/files/smoke" > /dev/null 2>&1
    cursor_before=$(cursor_setting)
    hidden_before=$(cursor_left_hidden)
    # Each smoke test runs in a process of its own, so a crash in one doesn't stop the others
    # (the list comes from am instrument -e log true, which names the tests without running them)
    run_instrument smoke-list 120 -e log true -e package com.limelight.smoke ${skip[@]+"${skip[@]}"}
    local test i=0
    : > "$DIR/smoke-results.txt"
    for test in $(grep -E '^(PASS|SKIP|FAIL) ' "$DIR/smoke-list-results.txt" | awk '{print $2}'); do
        i=$((i + 1))
        run_instrument "smoke-$i" "$SMOKE_TIMEOUT" -e class "$test" -e starwire.allowStream "$allow_stream" \
            ${skip[@]+"${skip[@]}"} "${watch[@]}" -e starwire.stuckSeconds 300
        verdict=$?
        [ $verdict = 1 ] && failed=1
        [ $verdict = 2 ] && crashed=1
        grep -v ' passed, ' "$DIR/smoke-$i-results.txt" >> "$DIR/smoke-results.txt"
    done
    if [ $i = 0 ]; then
        echo "RUN PROBLEM: no smoke tests found (smoke-list.txt)" >> "$DIR/smoke-results.txt"
        crashed=1
    fi
    local totals
    totals=$(awk '/^PASS / {p++} /^FAIL / {f++} /^SKIP / {s++} /^RUN PROBLEM/ {r = 1}
        END {printf "%d passed, %d failed, %d skipped%s\n", p, f, s, r ? ", run problem" : ""}' "$DIR/smoke-results.txt")
    echo "$totals" >> "$DIR/smoke-results.txt"
    cursor_after=$(cursor_setting)
    hidden_after=$(cursor_left_hidden)
    if [ "$cursor_before" != "$cursor_after" ]; then
        note "The Hide PC cursor setting changed from $cursor_before to $cursor_after"
        failed=1
    fi
    if [[ "$hidden_before$hidden_after" =~ ^[0-9]+$ ]] && [ "$hidden_after" -gt "$hidden_before" ]; then
        note "Starwire now thinks it left the PC's cursor hidden ($hidden_after PC(s), $hidden_before before)"
        failed=1
    fi
    adb_ pull "/sdcard/Android/data/$PKG/files/smoke" "$(host_path "$DIR")" > /dev/null 2>&1

    # 4. Random input, only where it can't reach a PC
    local monkey_line="not run"
    if [ "$monkey" -gt 0 ]; then
        if [ "$emulator" != yes ] && [ "$monkey_real" != 1 ]; then
            monkey_line="skipped: a real device (add --monkey-real-device to run it anyway)"
        else
            run_instrument monkey-check 120 -e class com.limelight.devicecheck.MonkeyPreconditionTest \
                -e starwire.monkeyCheck true
            # Only an explicit pass counts: a skip, a failure or a broken run all mean no monkey
            if ! grep -qx 'PASS com.limelight.devicecheck.MonkeyPreconditionTest#noSavedPc' "$DIR/monkey-check-results.txt" ||
               [ "$(tail -n 1 "$DIR/monkey-check-results.txt")" != "1 passed, 0 failed, 0 skipped" ]; then
                monkey_line="skipped: the app knows a PC, or the check didn't pass (monkey-check-results.txt)"
            else
                local airplane_was=""
                if [ "$emulator" = yes ] && [ "${sdk:-0}" -ge 30 ]; then
                    airplane_was=$(sh_ settings get global airplane_mode_on)
                    sh_ cmd connectivity airplane-mode enable > /dev/null 2>&1
                fi
                # Monkey reports a crash or ANR in any process, and used to stop at the first: on
                # the Android 15 emulator, its Bluetooth stack crashing as airplane mode went on
                # ended the run at event 4. It now goes on through other processes' crashes, and
                # only the app's own count.
                local keep_going=(--ignore-crashes --ignore-timeouts --ignore-native-crashes)
                if command -v timeout >/dev/null 2>&1; then
                    timeout 1800 "$ADB" -s "$SERIAL" shell monkey -p "$PKG" -s "$SEED" --throttle 150 \
                        --pct-syskeys 2 --pct-appswitch 2 --ignore-security-exceptions "${keep_going[@]}" \
                        --monitor-native-crashes -v -v "$monkey" 2>&1 | tr -d '\r' > "$DIR/monkey.txt"
                else
                    adb_ shell monkey -p "$PKG" -s "$SEED" --throttle 150 --pct-syskeys 2 --pct-appswitch 2 \
                        --ignore-security-exceptions "${keep_going[@]}" --monitor-native-crashes -v -v "$monkey" 2>&1 |
                        tr -d '\r' > "$DIR/monkey.txt"
                fi
                if [ -n "$airplane_was" ] && [ "$airplane_was" != 1 ]; then
                    sh_ cmd connectivity airplane-mode disable > /dev/null 2>&1
                fi
                local others
                others=$(grep -E '^// (CRASH|NOT RESPONDING): ' "$DIR/monkey.txt" | grep -vcE ": ${PKG//./\\.}( |\$)" || true)
                if grep -qE "^// (CRASH|NOT RESPONDING): ${PKG//./\\.}( |\$)" "$DIR/monkey.txt"; then
                    crashed=1
                    monkey_line="$monkey events, seed $SEED: found a crash or ANR (monkey.txt)"
                elif ! grep -q '^// Monkey finished' "$DIR/monkey.txt"; then
                    crashed=1
                    monkey_line="$monkey events, seed $SEED: monkey didn't finish (monkey.txt)"
                else
                    monkey_line="$monkey events, seed $SEED: no crash or ANR"
                    [ "${others:-0}" -gt 0 ] && monkey_line="$monkey_line (other processes crashed $others times: monkey.txt)"
                fi
            fi
        fi
    fi

    # 5. Crashes and ANRs from the app since the start. Some devices keep logcat empty, so
    # the system's dropbox is read too.
    local pkg_re="${PKG//./\\.}" tag
    adb_ logcat -d -b crash -T "$start_log" 2>/dev/null | tr -d '\r' > "$DIR/logcat-crash.txt"
    adb_ logcat -d -b main -b system -b events -T "$start_log" 2>/dev/null | tr -d '\r' > "$DIR/logcat.txt"
    : > "$DIR/dropbox.txt"
    for tag in data_app_crash data_app_anr data_app_native_crash; do
        sh_ dumpsys dropbox --print "$tag" >> "$DIR/dropbox.txt" 2>/dev/null
    done
    {
        grep -hE "^Process: $pkg_re\$|>>> $pkg_re <<<|ANR in $pkg_re( |\$)|am_anr.*,$pkg_re," \
            "$DIR/logcat-crash.txt" "$DIR/logcat.txt"
        awk -v start="$start_box" -v pkg="$PKG" '
            /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9] [0-9:]+ data_app_/ { stamp = $1 " " $2; tag = $3; next }
            /^Process: / { if (stamp != "" && stamp >= start && $2 == pkg) print stamp " " tag; stamp = "" }
        ' "$DIR/dropbox.txt"
    } > "$DIR/crashes.txt"
    [ -s "$DIR/crashes.txt" ] && crashed=1

    local shots crash_line
    shots=$(find "$DIR/smoke" -name '*.png' 2>/dev/null | wc -l | tr -d ' ')
    if [ -s "$DIR/crashes.txt" ]; then
        crash_line="$(wc -l < "$DIR/crashes.txt" | tr -d ' ') (crashes.txt)"
    else
        crash_line="none"
    fi
    if [ ! -s "$DIR/logcat.txt" ]; then
        crash_line="$crash_line; logcat gave nothing here, so only the system's crash records were read"
    fi
    {
        echo "Device:          $(cat "$DIR/device.txt")"
        if [ "$suite" = 1 ]; then
            echo "Suite:           $(tail -n 1 "$DIR/suite-results.txt")${only:+ (only $only)}"
            grep -E '^(FAIL|RUN PROBLEM)' "$DIR/suite-results.txt" | sed 's/^/                 /'
        fi
        echo "Smoke:           $(tail -n 1 "$DIR/smoke-results.txt")"
        grep -E '^(FAIL|SKIP|RUN PROBLEM)' "$DIR/smoke-results.txt" | sed 's/^/                 /'
        # What the smoke tests noticed along the way without failing over it
        if [ -f "$DIR/smoke/notes.txt" ]; then sed 's/^/                 Note: /' "$DIR/smoke/notes.txt"; fi
        echo "Hide PC cursor:  $cursor_before before, $cursor_after after; PCs left with a hidden cursor: ${hidden_after:-?}"
        echo "Monkey:          $monkey_line"
        echo "Crashes/ANRs:    $crash_line"
        echo "Screenshots:     $shots"
        cat "$DIR/notes.txt"
    } > "$DIR/summary.txt"

    [ $crashed = 1 ] && return 2
    [ $failed = 1 ] && return 1
    return 0
}

out="${out:-$ROOT/build/device-tests/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$out"
: > "$out/summary.txt"
worst=0
rank() {  # how bad an exit code is, for picking the worst device
    case "$1" in 2) echo 4 ;; 1) echo 3 ;; 3) echo 2 ;; 4) echo 1 ;; *) echo 0 ;; esac
}
for serial in "${serials[@]}"; do
    dir="$out/${serial//[^A-Za-z0-9._-]/_}"
    echo "== $serial"
    test_device "$serial" "$dir"
    code=$?
    {
        echo "== $serial (exit $code)"
        if [ -f "$dir/summary.txt" ]; then cat "$dir/summary.txt"; else cat "$dir/notes.txt"; fi
        echo
    } >> "$out/summary.txt"
    if [ "$(rank "$code")" -gt "$(rank "$worst")" ]; then worst=$code; fi
done
cat "$out/summary.txt"
echo "Results: $out"
exit "$worst"
