#!/usr/bin/env bash
# Checks that the scripts in .github/tools still work, so a moved file or a renamed app ID can't
# break one without anyone noticing (moving the tools here in 0a95c91a broke pacer-trace.sh that
# way). CI runs it on every push. It needs no device and no network.
#
#   .github/tools/check-tools.sh                      run the checks
#   .github/tools/check-tools.sh --make-pacer-sample  rewrite fixtures/pacer-trace-sample.csv
#
# Replaying the sample trace needs a C++17 compiler (CXX, or g++ or clang++ on PATH). Without one
# that check is skipped with a note, except when CI is set, where it fails.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SAMPLE="$HERE/fixtures/pacer-trace-sample.csv"
failures=0

fail() {
    echo "FAIL: $*" >&2
    failures=$((failures + 1))
}

# Each check starts with begin, and ends with ok, which says so if it found nothing wrong
section=0
begin() {
    section=$failures
}
ok() {
    if [ $failures -eq $section ]; then echo "ok: $*"; fi
}

# A made-up trace in the format pacer_trace.h describes: 4 s of 60 fps on a 60 Hz screen, each
# frame arriving 6 to 10 ms before the vsync it's shown at. Fixed numbers, nothing random.
make_pacer_sample() {
    awk 'BEGIN {
        period = 16666667; start = 10000000000; host = 5000000000; frames = 240
        print "# moonlight pacer trace v1"
        print "# Made up, not recorded: 4 s of a 60 fps stream on a 60 Hz screen, each frame arriving"
        print "# 6 to 10 ms before its vsync. check-tools.sh replays it to show that pacer-trace.sh and"
        print "# pacer_replay still work, and makes it again with: check-tools.sh --make-pacer-sample"
        print "C,mode=4,jitterBuffer=1,presentAhead=0,streamFps=60,width=1920,height=1080,periodNs=" period \
              ",refreshHz=60.000,tenBit=0,dither=0,device=sample"
        print "R," period
        for (i = 0; i < frames; i++) {
            vsync = start + i * period
            pts = host + i * period
            early = 6000000 + ((i * 7) % 5) * 1000000
            printf "F,%.0f,%.0f,%.0f,1,0\n", vsync - early, pts, vsync
            printf "V,%.0f,1,0,%.0f,%d,1,0,0,%.0f,%.0f,0.0,100000,0,0\n", vsync, pts, i >= 30, early, period
        }
    }'
}

if [ "${1:-}" = "--make-pacer-sample" ]; then
    mkdir -p "$(dirname "$SAMPLE")"
    make_pacer_sample > "$SAMPLE"
    echo "Wrote $SAMPLE"
    exit 0
fi

cd "$ROOT"

# 1. Every shell script in the repository parses
begin
scripts=$(git ls-files '*.sh' 2>/dev/null || find . -name '*.sh' -not -path './.git/*')
count=0
for f in $scripts; do
    count=$((count + 1))
    bash -n "$f" || fail "$f doesn't parse"
done
ok "$count shell scripts parse"

# 2. The workflows and the tools only call tools that exist. Docs only get a note: they may name
# a script that's still being written.
begin
missing() {
    grep -oh '\.github/tools/[A-Za-z0-9_./-]*' "$@" 2>/dev/null | sed 's/[.]*$//' | sort -u |
        while read -r path; do [ -e "$path" ] || echo "$path"; done
}
for path in $(missing .github/workflows/*.yml $(git ls-files .github/tools)); do
    fail "the workflows or tools use $path, which doesn't exist"
done
for path in $(missing $(git ls-files '*.md')); do
    echo "note: a doc mentions $path, which doesn't exist (yet)"
done
ok "the workflows and tools only use tools that exist"

# 3. App IDs the tools default to are ones the build makes (pacer-trace.sh kept Moonlight X's)
begin
ids=$(grep -o 'applicationId "[^"]*"' app/build.gradle | cut -d'"' -f2 | tr '\n' ' ' | sed 's/ $//')
suffix=$(grep -o 'applicationIdSuffix "[^"]*"' app/build.gradle | cut -d'"' -f2 | head -n 1)
for tool in .github/tools/*.sh; do
    for id in $(grep -o ':-com\.[A-Za-z0-9_.]*}' "$tool" | sed 's/^:-//; s/}$//'); do
        known=no
        for app in $ids; do
            case "$id" in "$app" | "$app$suffix" | "$app$suffix.test") known=yes ;; esac
        done
        [ "$known" = yes ] || fail "$tool defaults to $id, which the build doesn't make ($ids, and $suffix)"
    done
done
ok "the tools' default app IDs are this app's"

# 4. pacer-trace.sh prints its help without a device
begin
set +e
help=$(bash .github/tools/pacer-trace.sh 2>&1)
status=$?
set -e
if [ $status -ne 2 ] || ! printf '%s\n' "$help" | grep -q 'analyze \[file'; then
    fail "pacer-trace.sh without a command should print its help and exit 2 (exit $status)"
else
    ok "pacer-trace.sh prints its help"
fi

# 5. The sample trace is the one make_pacer_sample makes
begin
if make_pacer_sample | diff -q --strip-trailing-cr - "$SAMPLE" >/dev/null; then
    ok "the sample trace is up to date"
else
    fail "$SAMPLE differs from what --make-pacer-sample makes"
fi

# 6. pacer-trace.sh builds the replay tool from this checkout and replays the sample
begin
cxx="${CXX:-}"
if [ -z "$cxx" ]; then
    for candidate in g++ clang++; do
        if command -v "$candidate" >/dev/null 2>&1; then cxx="$candidate"; break; fi
    done
fi
if [ -z "$cxx" ]; then
    if [ -n "${CI:-}" ]; then
        fail "no C++ compiler to build the pacer replay tool"
    else
        echo "skipped: replaying the sample trace (no C++ compiler; set CXX)"
    fi
else
    work="$(mktemp -d)"
    set +e
    replay=$(CXX="$cxx" PACER_TRACE_DIR="$work" ADB=/nonexistent/adb \
        bash .github/tools/pacer-trace.sh analyze "$SAMPLE" 2>&1)
    status=$?
    set -e
    rm -rf "$work"
    if [ $status -ne 0 ]; then
        printf '%s\n' "$replay" >&2
        fail "pacer-trace.sh analyze failed on the sample trace (exit $status)"
    elif ! printf '%s\n' "$replay" | grep -q 'Replayed through the current pacer' ||
         ! printf '%s\n' "$replay" | grep -q ' of 240 vsync decisions differ'; then
        printf '%s\n' "$replay" >&2
        fail "pacer-trace.sh analyze didn't replay all 240 vsyncs of the sample trace"
    else
        ok "pacer-trace.sh replays the sample trace"
    fi
fi

# 7. parse-instrument.sh gives the right verdict on am instrument output recorded on a tablet
begin
verdict() {  # fixture, expected exit code, a line its summary must have
    local out status
    set +e
    out=$(bash .github/tools/parse-instrument.sh "$HERE/fixtures/$1" 2>&1)
    status=$?
    set -e
    if [ $status -ne "$2" ] || ! grep -qxF "$3" <<< "$out"; then
        printf '%s\n' "$out" >&2
        fail "parse-instrument.sh on $1: exit $status (expected $2), or no line \"$3\""
    fi
}
verdict instrument-pass.txt 0 "1 passed, 0 failed, 0 skipped"
verdict instrument-mixed.txt 1 "SKIP com.limelight.devicecheck.MonkeyPreconditionTest#noSavedPc (only for device-test.sh --monkey)"
verdict instrument-mixed.txt 1 "1 passed, 1 failed, 1 skipped"
verdict instrument-crash.txt 2 "RUN PROBLEM: Process crashed."
verdict instrument-none.txt 2 "RUN PROBLEM: no tests ran"
# A test the watchdog (StuckTestWatchdog) caught hanging fails, even when it passes after that
verdict instrument-stuck.txt 2 "FAIL com.limelight.AppViewTest#tileMenuOpensOnIconTiles: stuck for 120 s; its activities were finished so the run could go on"
verdict instrument-stuck.txt 2 "PASS com.limelight.AppViewTest#offscreenDrawingShowsGpuBitmaps"
verdict instrument-stuck.txt 2 "FAIL com.limelight.AppViewTest#appListOpensWithoutClosing: the run stopped during this test (still stuck 30 s after its activities were finished: the main thread is blocked, so the process was ended)"
verdict instrument-stuck.txt 2 "1 passed, 2 failed, 0 skipped, run problem"
ok "parse-instrument.sh reads passes, skips, failures, a crash, an empty run and stuck tests"

# 8. device-test.sh, against a stand-in for adb (fixtures/fake-adb.sh): it refuses listed
# devices without sending them anything, stops for a locked screen or an open stream, never
# uninstalls, and sends random input only where MonkeyPreconditionTest passed
begin
work="$(mktemp -d)"
printf '#!/usr/bin/env bash\nexec bash "%s" "$@"\n' "$HERE/fixtures/fake-adb.sh" > "$work/adb"
chmod +x "$work/adb"
mkdir -p "$work/apk/nonRoot/debug" "$work/apk/androidTest/nonRoot/debug"
touch "$work/apk/nonRoot/debug/app-nonRoot-debug.apk" \
      "$work/apk/androidTest/nonRoot/debug/app-nonRoot-debug-androidTest.apk"
: > "$work/all-calls"
fake_run() {  # expected exit code, NAME=value settings, then device-test.sh's arguments
    local expected=$1 status
    shift
    local settings=()
    while [[ "${1:-}" =~ ^[A-Z_][A-Z0-9_]*= ]]; do settings+=("$1"); shift; done
    : > "$work/calls"
    set +e
    env "${settings[@]}" FAKE_ADB_LOG="$work/calls" ADB="$work/adb" \
        bash .github/tools/device-test.sh --apk-dir "$work/apk" --out "$work/out" "$@" > "$work/output" 2>&1
    status=$?
    set -e
    cat "$work/calls" >> "$work/all-calls"
    if [ $status -ne "$expected" ]; then
        cat "$work/output" >&2
        fail "device-test.sh ${settings[*]} $*: exit $status, expected $expected"
    fi
}
called() {
    grep -qF -- "$1" "$work/calls"
}
denied_hash="sha256:$(printf '%s' FAKE-DENIED | sha256sum | cut -d' ' -f1)"
for deny in FAKE-DENIED Fake_Phone "$denied_hash"; do
    fake_run 4 STARWIRE_DENY_DEVICES="$deny" -s FAKE-DENIED
    if called "-s FAKE-DENIED"; then fail "device-test.sh sent a refused device (by $deny) a command"; fi
done
fake_run 4 STARWIRE_DENY_DEVICES=FAKESER -s adb-FAKESER-x1y2._adb-tls-connect._tcp
if called "-s adb-FAKESER"; then fail "device-test.sh sent a refused wireless device a command"; fi
# The refused devices come from a git-ignored file, so no device IDs are published in the script
if ! grep -qx 'DENY_DEVICES=""' .github/tools/device-test.sh || grep -qE 'sha256:[0-9a-f]{64}' .github/tools/device-test.sh; then
    fail "device-test.sh lists device IDs itself; they belong in private/deny-devices.txt"
fi
printf '# a comment\n  FAKE-DENIED  \r\n' > "$work/deny-devices.txt"
fake_run 4 STARWIRE_DENY_FILE="$work/deny-devices.txt" -s FAKE-DENIED
if called "-s FAKE-DENIED"; then fail "device-test.sh sent a device listed in its deny file a command"; fi
fake_run 0 STARWIRE_DENY_FILE="$work/no-such-file.txt" -s FAKE-1
if ! called "-s FAKE-1 install"; then fail "device-test.sh didn't test a device when its deny file is missing"; fi
# Several devices in one run: the refused one is skipped, the other tested, the worst exit wins
fake_run 4 STARWIRE_DENY_DEVICES=FAKE-DENIED -s FAKE-1 -s FAKE-DENIED
if ! called "-s FAKE-1 install"; then fail "device-test.sh didn't test the allowed device of two"; fi
if called "-s FAKE-DENIED"; then fail "device-test.sh sent a refused device a command in a run of two"; fi
fake_run 4 FAKE_ADB_LOCKED=1 -s FAKE-1
if called " install "; then fail "device-test.sh installed on a locked device"; fi
fake_run 4 FAKE_ADB_STREAMING=1 -s FAKE-1
if called " install "; then fail "device-test.sh went on while a stream was open"; fi
fake_run 3 FAKE_ADB_INSTALL=fail -s FAKE-1
# A real device: random input needs --monkey-real-device as well, so nothing is checked or sent
fake_run 0 FAKE_ADB_MONKEY_CHECK=pass -s FAKE-1 --monkey 50
if called "MonkeyPreconditionTest" || called "shell monkey"; then
    fail "device-test.sh ran monkey on a real device without --monkey-real-device"
fi
# An emulator where the app knows a PC: the check fails, so no random input
fake_run 0 FAKE_ADB_EMULATOR=1 FAKE_ADB_MONKEY_CHECK=fail -s FAKE-1 --monkey 50
if ! called "-e starwire.monkeyCheck true"; then fail "device-test.sh didn't run MonkeyPreconditionTest"; fi
if called "shell monkey"; then fail "device-test.sh ran monkey although MonkeyPreconditionTest failed"; fi
# An emulator that knows no PC: monkey runs with the fixed seed, in airplane mode, put back after
fake_run 0 FAKE_ADB_EMULATOR=1 FAKE_ADB_MONKEY_CHECK=pass -s FAKE-1 --monkey 50
if ! called "shell monkey -p com.starwire.debug -s 20261023" || ! called "airplane-mode enable" ||
   ! called "airplane-mode disable"; then
    fail "device-test.sh didn't run monkey with the fixed seed in airplane mode on an empty emulator"
fi
# Monkey goes on through another process's crash (the emulator's Bluetooth stack) and only the
# app's own crash fails the run
fake_run 0 FAKE_ADB_EMULATOR=1 FAKE_ADB_MONKEY_CHECK=pass FAKE_ADB_MONKEY=other -s FAKE-1 --monkey 50
if ! called "--ignore-native-crashes" || ! grep -q 'no crash or ANR (other processes crashed 1 times' "$work/out/FAKE-1/summary.txt"; then
    fail "device-test.sh counted another process's crash during monkey as the app's"
fi
fake_run 2 FAKE_ADB_EMULATOR=1 FAKE_ADB_MONKEY_CHECK=pass FAKE_ADB_MONKEY=app -s FAKE-1 --monkey 50
grep -q 'found a crash or ANR' "$work/out/FAKE-1/summary.txt" || fail "device-test.sh missed the app crashing during monkey"
# @HardwareOnly tests are left out on an emulator only, and --only narrows the suite to its classes
fake_run 0 -s FAKE-1
if called "notAnnotation"; then fail "device-test.sh left out @HardwareOnly tests on a real device"; fi
if ! called "-e notPackage com.limelight.smoke,com.limelight.devicecheck"; then
    fail "device-test.sh didn't run the whole instrumented suite"
fi
# Under the watchdog that fails a hanging test instead of letting it stop the run
watch="-e listener com.limelight.testing.StuckTestWatchdog"
if ! called "-e notPackage com.limelight.smoke,com.limelight.devicecheck $watch" ||
   ! called "-e starwire.allowStream false $watch -e starwire.stuckSeconds 300"; then
    fail "device-test.sh didn't run the suite and the smoke tests under StuckTestWatchdog"
fi
fake_run 0 FAKE_ADB_EMULATOR=1 -s FAKE-1 --only com.limelight.ScreenWidthTest,com.limelight.StringsTest
hw="-e notAnnotation com.limelight.testing.HardwareOnly"
if ! called "-e class com.limelight.ScreenWidthTest,com.limelight.StringsTest $hw" || called "notPackage" ||
   ! called "-e starwire.allowStream false $hw"; then
    fail "device-test.sh --only on an emulator didn't run just those classes, without @HardwareOnly tests"
fi
fake_run 4 -s FAKE-1 --only "com.limelight.X; rm -rf /"
# The logs are read the way Android 6 understands too: one buffer per -b
fake_run 0 FAKE_ADB_SDK=23 -s FAKE-1
if grep -q 'Unable to open log device' "$work/out/FAKE-1/logcat.txt" || ! grep -q 'ActivityManager: fake' "$work/out/FAKE-1/logcat.txt"; then
    fail "device-test.sh didn't read the main, system and events logs the way Android 6 takes them"
fi
# The app's Java crash in the crash log, as logcat prints it, fails the run
: > "$work/crash"
fake_run 2 -s FAKE-1
grep -qx 'Crashes/ANRs:    1 (crashes.txt)' "$work/out/FAKE-1/summary.txt" ||
    fail "device-test.sh missed the app's crash in logcat's crash log"
# So does a crash check that couldn't keep the crash log: that isn't a run with no crash
rm -f "$work/out/FAKE-1/logcat-crash.txt"
mkdir "$work/out/FAKE-1/logcat-crash.txt"
fake_run 2 -s FAKE-1
if grep -q '^Crashes/ANRs:    none' "$work/out/FAKE-1/summary.txt"; then
    fail "device-test.sh said there was no crash, but it couldn't keep the crash log"
fi
rmdir "$work/out/FAKE-1/logcat-crash.txt"
rm -f "$work/crash"
if grep -q uninstall "$work/all-calls"; then fail "device-test.sh uninstalled something"; fi
ok "device-test.sh refuses listed devices, checks before it installs, guards monkey, and filters tests"

# 9. Every action the workflows use is pinned to a full commit hash with its release in a
# comment, and each action to the same commit in every workflow
begin
while IFS= read -r line; do
    use=$(sed -E 's/^[^:]*:[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]*//' <<< "$line")
    case "$use" in ./*) continue ;; esac   # a workflow in this repo
    if ! [[ "$use" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}\ \#\ v[0-9]+(\.[0-9]+)*$ ]]; then
        fail "${line%%:*}: $use isn't pinned to a full commit hash with its version in a comment"
    fi
done < <(grep -HE '^[[:space:]]*(-[[:space:]]+)?uses:' .github/workflows/*.yml)
for action in $(grep -hoE 'uses:[[:space:]]*[^ ]+@[0-9a-f]{40}' .github/workflows/*.yml | sed -E 's/uses:[[:space:]]*//' |
        sort -u | cut -d@ -f1 | uniq -d); do
    fail "$action is pinned to different commits in different workflows"
done
ok "the workflows' actions are pinned to commits"

# 9b. Every artifact upload says how long GitHub keeps it. The default is 90 days, and old uploads
# had filled the private repo's storage past its free allowance.
begin
uploads_without_retention() {  # prints file:line of each upload-artifact step with no retention-days
    awk '
        FNR == 1 { if (step != "" && !kept) print step; step = ""; kept = 0 }
        /^[[:space:]]*-[[:space:]]/ { if (step != "" && !kept) print step; step = ""; kept = 0 }
        /uses:[[:space:]]*actions\/upload-artifact@/ { step = FILENAME ":" FNR }
        /^[[:space:]]*retention-days:[[:space:]]*[0-9]+/ { kept = 1 }
        END { if (step != "" && !kept) print step }' "$@"
}
for m in $(uploads_without_retention .github/workflows/*.yml); do
    fail "$m: this upload-artifact step has no retention-days"
done
printf '%s\n' 'jobs:' '  a:' '    steps:' '      - uses: actions/upload-artifact@x' '        with:' \
    '          name: n' '      - run: true' > "$work/no-retention.yml"
[ -n "$(uploads_without_retention "$work/no-retention.yml")" ] ||
    fail "the retention-days check missed a planted upload without one"
ok "every artifact upload says how long it's kept"

# 10. device-matrix.py: the matrix is valid, the tiers nest, and each event gets the tier it should
begin
py=""
for candidate in python3 python py; do
    if "$candidate" -c 'import sys; sys.exit(sys.version_info < (3, 8))' > /dev/null 2>&1; then py=$candidate; break; fi
done
dm() {
    "$py" .github/tools/device-matrix.py "$@"
}
exits() {  # expected exit code, then a command
    local expected=$1 status
    shift
    set +e
    "$@" > "$work/dm-output" 2>&1
    status=$?
    set -e
    [ $status -eq "$expected" ] || { cat "$work/dm-output" >&2; fail "$*: exit $status, expected $expected"; }
}
if [ -z "$py" ]; then
    if [ -n "${CI:-}" ]; then fail "no Python 3 for device-matrix.py"; else echo "skipped: device-matrix.py (no Python 3)"; fi
else
    count() { dm plan --tier "$1" | sed -n 's/^count=//p'; }
    legs=$("$py" -c 'import json, sys; print(len(json.load(open(sys.argv[1], encoding="utf-8"))["legs"]))' \
        .github/devices/matrix.json)
    core=$(count core) wide=$(count wide) release=$(count release)
    if [ "$(count none)" != 0 ] || [ "$core" -lt 1 ] || [ "$core" -gt "$wide" ] || [ "$wide" -gt "$release" ] ||
       [ "$release" != "$legs" ]; then
        fail "device-matrix.py plan: tiers none/core/wide/release gave $(count none)/$core/$wide/$release of $legs legs"
    fi
    [ "$(dm plan --tier core --legs tv-30,fold-z-36 | sed -n 's/^legs=//p')" = "fold-z-36,tv-30" ] ||
        fail "device-matrix.py plan --legs didn't pick just those legs"
    exits 2 dm plan --tier core --legs no-such-leg
    # Matrices it has to refuse: an unknown variant, random input where the network stays on,
    # two legs with one id, an ARM image the runners can't accelerate, a missing layout test, a
    # data partition size that isn't in MB
    for change in 's/"font-1.3"/"font-huge"/' \
                  's/"api": "23", "target": "default", "arch": "x86_64",/&\n      "monkey": 50,/' \
                  's/"id": "tv-34"/"id": "tv-30"/' \
                  's/"api": "21", "target": "default", "arch": "x86_64"/"api": "21", "target": "default", "arch": "arm64-v8a"/' \
                  's/"com.limelight.StringsTest"/"com.limelight.NoSuchTest"/' \
                  's/"id": "legacy-23",/&\n      "disk": "4G",/'; do
        sed "$change" .github/devices/matrix.json > "$work/matrix.json"
        if cmp -s "$work/matrix.json" .github/devices/matrix.json; then fail "the matrix change $change changed nothing"; fi
        exits 2 dm --matrix "$work/matrix.json" plan --tier release
    done
    tier_is() {  # expected tier, then device-matrix.py tier's options
        local want=$1 got
        shift
        got=$(dm tier "$@" 2>/dev/null | sed -n 's/^tier=//p')
        [ "$got" = "$want" ] || fail "device-matrix.py tier $*: ${got:-nothing}, expected $want"
    }
    pub=SicarioGaming/Starwire priv=SicarioGaming/Starwire-private
    tier_is core    --event pull_request --action opened --repo $pub
    tier_is none    --event pull_request --action synchronize --repo $priv
    tier_is core    --event pull_request --action synchronize --repo $priv --labels '["bug", "device-tests"]'
    tier_is core    --event pull_request --action labeled --label device-tests --repo $priv
    tier_is none    --event pull_request --action labeled --label bug --repo $pub
    tier_is none    --event pull_request --action opened --repo someone/Starwire
    tier_is none    --event pull_request --action opened --repo $pub --switch off
    tier_is wide    --event schedule --repo $pub
    tier_is none    --event schedule --repo $priv
    tier_is release --event push --repo $pub
    tier_is none    --event push --repo $priv
    tier_is core    --event workflow_dispatch --repo $priv
    tier_is release --event workflow_dispatch --input-tier release --repo $priv
    tier_is core    --event workflow_dispatch --input-tier core --repo $priv --switch off
    exits 2 dm tier --event workflow_dispatch --input-tier everything
    # The handhelds' screens and every leg's data partition go into the AVD's config.ini, the same
    # way every time, and a key the emulator action appended again is left once, with its last value
    # (unset, the emulator wanted 12288 MB free for the data partition and quit on every leg)
    mkdir -p "$work/avd/handheld-33.avd" "$work/avd/legacy-23.avd"
    printf 'hw.lcd.width=1080\nhw.lcd.height=2400\nhw.ramSize=2048\ndisk.dataPartition.size=10G\nhw.cpu.ncore=2\nhw.ramSize=2G\n' \
        > "$work/avd/handheld-33.avd/config.ini"
    echo '{"id": "handheld-33"}' > "$work/leg.json"
    dm avd-config "$work/leg.json" --avd-home "$work/avd" > /dev/null
    cp "$work/avd/handheld-33.avd/config.ini" "$work/config-once.ini"
    printf 'hw.cpu.ncore=2\nhw.ramSize=2G\n' >> "$work/avd/handheld-33.avd/config.ini"
    dm avd-config "$work/leg.json" --avd-home "$work/avd" > /dev/null
    if ! cmp -s "$work/config-once.ini" "$work/avd/handheld-33.avd/config.ini" ||
       ! grep -qx 'hw.lcd.width=1920' "$work/config-once.ini" || grep -qx 'hw.lcd.width=1080' "$work/config-once.ini" ||
       [ "$(grep -c '^hw.ramSize=' "$work/config-once.ini")" != 1 ] || ! grep -qx 'hw.ramSize=2G' "$work/config-once.ini" ||
       [ "$(grep -c '^disk.dataPartition.size=' "$work/config-once.ini")" != 1 ] ||
       ! grep -qx 'disk.dataPartition.size=6442450944' "$work/config-once.ini" || ! grep -qx 'hw.cpu.ncore=2' "$work/config-once.ini"; then
        cat "$work/config-once.ini" >&2
        fail "device-matrix.py avd-config didn't set the handheld's screen and data partition once, keeping the rest"
    fi
    printf 'hw.ramSize=2048M\n' > "$work/avd/legacy-23.avd/config.ini"
    echo '{"id": "legacy-23"}' > "$work/legacy.json"
    dm avd-config "$work/legacy.json" --avd-home "$work/avd" > /dev/null
    grep -qx 'disk.dataPartition.size=6442450944' "$work/avd/legacy-23.avd/config.ini" ||
        fail "device-matrix.py avd-config didn't set the data partition of a leg with no hardware overrides"
    # The AVD cache key follows the system image's revision
    mkdir -p "$work/sdk/system-images/android-33/google_apis/x86_64" "$work/sdk/emulator"
    echo 'Pkg.Revision=36.1.9' > "$work/sdk/emulator/source.properties"
    echo 'Pkg.Revision=17' > "$work/sdk/system-images/android-33/google_apis/x86_64/source.properties"
    key17=$(dm cache-key "$work/leg.json" --sdk "$work/sdk" | sed -n 's/^key=//p')
    echo 'Pkg.Revision=18' > "$work/sdk/system-images/android-33/google_apis/x86_64/source.properties"
    key18=$(dm cache-key "$work/leg.json" --sdk "$work/sdk" | sed -n 's/^key=//p')
    case "$key17" in avd-v1-handheld-33-img17-emu36.1.9-*) ;; *) fail "device-matrix.py cache-key gave $key17" ;; esac
    [ "$key17" != "$key18" ] || fail "device-matrix.py cache-key didn't change with the image's revision"
    sed 's/"id": "handheld-33",/&\n      "disk": "8192M",/' .github/devices/matrix.json > "$work/matrix.json"
    [ "$(dm --matrix "$work/matrix.json" cache-key "$work/leg.json" --sdk "$work/sdk" | sed -n 's/^key=//p')" != "$key18" ] ||
        fail "device-matrix.py cache-key didn't change with the data partition's size"
    # --check-sdk reads sdkmanager --list from command-line tools 22.0 (a table) and from 23.0 (the
    # Android CLI's list): every leg's image is there, Android 16 Google APIs for x86 isn't, and a
    # listing with no images at all fails instead of reading as missing images
    bin="$work/check-sdk/cmdline-tools/latest/bin"
    mkdir -p "$bin"
    for t in avdmanager sdkmanager; do
        printf '#!/bin/sh\ncat "$(dirname "$0")/%s.txt"\n' "$t" > "$bin/$t"
        printf '@type "%%~dp0%s.txt"\r\n' "$t" > "$bin/$t.bat"
        chmod +x "$bin/$t"
    done
    cp .github/tools/fixtures/avdmanager-devices.txt "$bin/avdmanager.txt"
    sed '/"id": "fold-z-36"/,/"arch"/s/"arch": "x86_64"/"arch": "x86"/' .github/devices/matrix.json > "$work/matrix.json"
    for listing in sdkmanager-list-22.txt sdkmanager-list-23.txt; do
        cp ".github/tools/fixtures/$listing" "$bin/sdkmanager.txt"
        exits 0 dm plan --tier release --check-sdk "$work/check-sdk"
        exits 2 dm --matrix "$work/matrix.json" plan --tier core --check-sdk "$work/check-sdk"
        if [ "$(grep -c 'has no' "$work/dm-output")" != 1 ] ||
           ! grep -Eq 'fold-z-36: sdkmanager has no system-images;android-36;google_apis;x86\s*$' "$work/dm-output"; then
            cat "$work/dm-output" >&2
            fail "device-matrix.py --check-sdk with $listing didn't find just fold-z-36's image missing"
        fi
    done
    cp .github/tools/fixtures/avdmanager-devices.txt "$bin/sdkmanager.txt"
    exits 2 dm plan --tier core --check-sdk "$work/check-sdk"
    grep -q 'named no system images' "$work/dm-output" || fail "device-matrix.py --check-sdk took a listing with no images"
fi
ok "device-matrix.py validates the matrix, plans the tiers and picks each event's tier"

# 11. device-sdk.sh puts command-line tools 23.0 in cmdline-tools/latest once, over the runner's
# 12.0 (whose avdmanager has no pixel_fold), against a stand-in for sdkmanager
begin
sdk="$work/fake-sdk"
mkdir -p "$sdk/cmdline-tools/latest/bin"
echo 'Pkg.Revision=12.0' > "$sdk/cmdline-tools/latest/source.properties"
cat > "$sdk/cmdline-tools/latest/bin/sdkmanager" <<'EOF'
#!/usr/bin/env bash
sdk="$(cd "$(dirname "$0")/../../.." && pwd)"
echo "$*" >> "$sdk/sdkmanager.log"
for arg in "$@"; do
    case "$arg" in
        "cmdline-tools;"*)
            v=${arg#*;}
            mkdir -p "$sdk/cmdline-tools/$v/bin"
            echo "Pkg.Revision=$v" > "$sdk/cmdline-tools/$v/source.properties"
            cp "$0" "$sdk/cmdline-tools/$v/bin/sdkmanager"
            printf '#!/bin/sh\n' > "$sdk/cmdline-tools/$v/bin/avdmanager"
            chmod +x "$sdk/cmdline-tools/$v/bin/"* ;;
    esac
done
EOF
chmod +x "$sdk/cmdline-tools/latest/bin/sdkmanager"
first=$(ANDROID_HOME="$sdk" bash .github/tools/device-sdk.sh cmdline-tools 2>&1 || true)
again=$(ANDROID_HOME="$sdk" bash .github/tools/device-sdk.sh cmdline-tools 2>&1 || true)
if [ "$first" != "command-line tools 12.0 -> 23.0" ] || [ "$again" != "command-line tools 23.0" ] ||
   ! grep -qx 'Pkg.Revision=23.0' "$sdk/cmdline-tools/latest/source.properties" ||
   [ -e "$sdk/cmdline-tools/23.0" ] || [ "$(grep -c -- '--install' "$sdk/sdkmanager.log")" != 1 ]; then
    printf '%s\n%s\n' "$first" "$again" >&2
    fail "device-sdk.sh didn't swap command-line tools 12.0 for 23.0 exactly once"
fi
ok "device-sdk.sh swaps in command-line tools 23.0"

# 11b. device-watch.sh ends the emulator step a few seconds in when the emulator has quit (no
# process, nothing in adb devices), and leaves a booted one alone, against a stand-in for adb
begin
if ! command -v pgrep > /dev/null 2>&1; then
    if [ -n "${CI:-}" ]; then fail "no pgrep for device-watch.sh"; else echo "skipped: device-watch.sh (no pgrep)"; fi
else
    wsdk="$work/watch-sdk"
    mkdir -p "$wsdk/platform-tools"
    cat > "$wsdk/platform-tools/adb" <<'ADB'
#!/bin/sh
case "$*" in
    *boot_completed*) [ -n "${FAKE_BOOTED:-}" ] && echo 1 ;;
    devices) echo "List of devices attached" ;;
esac
exit 0
ADB
    chmod +x "$wsdk/platform-tools/adb"
    # Stands in for the emulator action's node process, which the watcher stops
    bash -c 'while :; do sleep 1; done' _actions/ReactiveCircus/android-emulator-runner/stand-in &
    action=$!
    set +e
    ANDROID_HOME="$wsdk" GRACE=1 INTERVAL=1 LIMIT=20 \
        bash .github/tools/device-watch.sh --out "$work/watch" --watch > "$work/watch.log" 2>&1
    status=$?
    sleep 1
    kill -0 "$action" 2> /dev/null
    alive=$?
    set -e
    if [ $status -ne 1 ] || [ $alive -eq 0 ] || ! grep -q 'quit before it booted' "$work/watch/emulator-quit.txt"; then
        cat "$work/watch.log" >&2
        fail "device-watch.sh didn't stop the action when the emulator had quit"
    fi
    kill "$action" 2> /dev/null || true
    wait "$action" 2> /dev/null || true
    set +e
    FAKE_BOOTED=1 ANDROID_HOME="$wsdk" GRACE=1 INTERVAL=1 LIMIT=20 \
        bash .github/tools/device-watch.sh --out "$work/watch" --watch > "$work/watch.log" 2>&1
    status=$?
    set -e
    if [ $status -ne 0 ] || [ -e "$work/watch/emulator-quit.txt" ]; then
        cat "$work/watch.log" >&2
        fail "device-watch.sh didn't leave a booted emulator alone"
    fi
    ok "device-watch.sh stops the emulator step when the emulator quits before booting"
fi

# 12. device-run.sh, against the fake adb: on a real device it only does the normal run and
# sends nothing itself; on an emulator it folds, changes text and display size, puts each
# setting back, runs the random input last, and its results read as they should
begin
if [ -z "$py" ]; then
    if [ -n "${CI:-}" ]; then fail "no Python 3 for device-run.sh"; else echo "skipped: device-run.sh (no Python 3)"; fi
else
    leg_run() {  # expected exit code, NAME=value settings, then device-run.sh's arguments
        local expected=$1 status state
        shift
        local settings=()
        while [[ "${1:-}" =~ ^[A-Z_][A-Z0-9_]*= ]]; do settings+=("$1"); shift; done
        : > "$work/calls"
        state=$(mktemp -d "$work/state.XXXXXX")
        set +e
        env ${settings[@]+"${settings[@]}"} FAKE_ADB_LOG="$work/calls" FAKE_ADB_STATE="$state" ADB="$work/adb" \
            bash .github/tools/device-run.sh --apk-dir "$work/apk" "$@" > "$work/output" 2>&1
        status=$?
        set -e
        cat "$work/calls" >> "$work/all-calls"
        if [ $status -ne "$expected" ]; then
            cat "$work/output" >&2
            fail "device-run.sh ${settings[*]} $*: exit $status, expected $expected"
        fi
    }
    result() {  # leg folder, pass name: that pass's result
        awk -F'\t' -v n="$2" '$1 == n {print $2}' "$work/legs/$1/passes.tsv"
    }
    leg_run 0 -s FAKE-1 --leg tablet-35 --out "$work/legs/real"
    for sent in "settings put" "wm density " " emu " "uimode" "set-app-locales" "svc power" "am start" "logcat -c"; do
        if called "$sent"; then fail "device-run.sh sent a real device '$sent'"; fi
    done
    if [ "$(result real base)" != PASS ] || [ "$(result real dark)" != SKIP ]; then
        fail "device-run.sh on a real device didn't do just the normal run"
    fi
    leg_run 4 STARWIRE_DENY_DEVICES=FAKE-DENIED -s FAKE-DENIED --leg tablet-35 --out "$work/legs/denied"
    if called "-s FAKE-DENIED"; then fail "device-run.sh sent a refused device a command"; fi
    leg_run 4 FAKE_ADB_OFFLINE=1 -s emulator-5554 --leg legacy-23 --out "$work/legs/offline"
    [ -e "$work/legs/offline/infra-failure" ] || fail "device-run.sh didn't mark a missing emulator for a retry"
    leg_run 2 FAKE_ADB_EMULATOR=1 FAKE_ADB_CRASH_ON=fold FAKE_ADB_SIZE=1768x2208 FAKE_ADB_DENSITY=420 \
        -s emulator-5554 --leg fold-z-36 --out "$work/legs/fold"
    if ! called "emu fold" || ! called "cmd device_state state 1" || ! called "emu unfold" ||
       ! called "cmd device_state state reset" || ! called "-e class com.limelight.ScreenWidthTest," ||
       [ "$(result fold folded)" != CRASH ] || [ "$(result fold half-open)" != PASS ]; then
        fail "device-run.sh didn't fold, half-open and unfold the emulator, or missed the crash on folding"
    fi
    leg_run 0 FAKE_ADB_EMULATOR=1 FAKE_ADB_MONKEY_CHECK=pass -s emulator-5554 --leg phone-small-35 --out "$work/legs/small"
    if ! called "settings put system font_scale 1.3" || ! called "settings put system font_scale 1.0" ||
       ! called "wm density 480" || ! called "wm density reset" ||
       ! called "settings put global stay_on_while_plugged_in 0" ||
       ! grep -qE 'shell monkey -p com.starwire.debug -s 20261023 .* 1000$' "$work/calls" ||
       [ "$(tail -n 1 "$work/legs/small/passes.tsv" | cut -f 1,2)" != "$(printf 'monkey\tPASS')" ]; then
        fail "device-run.sh didn't change and put back text and display size, or run monkey last"
    fi
    leg_run 0 FAKE_ADB_EMULATOR=1 FAKE_ADB_ABI=fail -s emulator-5554 --leg arm64-translated-30 --out "$work/legs/abi"
    [ "$(result abi base)" = SKIP ] || fail "device-run.sh didn't skip arm64 code on an image that can't run it"
    # An emulator still locked right after the unlock gets a few tries; the lock screen and the
    # system's error dialogs are off for the leg, and come back after it
    leg_run 0 FAKE_ADB_EMULATOR=1 FAKE_ADB_LOCKED=slow -s emulator-5554 --leg legacy-23 --out "$work/legs/locked"
    if [ "$(result locked base)" != PASS ] || ! called "locksettings set-disabled true" ||
       ! called "locksettings set-disabled false" || ! called "settings put global hide_error_dialogs 1" ||
       ! called "settings delete global hide_error_dialogs"; then
        fail "device-run.sh didn't wait out a slow unlock, or didn't turn the lock screen and error dialogs off and back"
    fi
    leg_run 1 FAKE_ADB_EMULATOR=1 FAKE_ADB_FAIL=suite -s emulator-5554 --leg legacy-23 --out "$work/legs/fail"
    grep -q '^base: FAIL ' "$work/legs/fail/failed-tests.txt" || fail "device-run.sh didn't list the failed test"
    # What the workflow shows: a leg's verdict, and the table for all of them
    exits 0 dm verdict "$work/legs/small"
    exits 1 dm verdict "$work/legs/fail"
    exits 0 dm verdict "$work/legs/abi"
    mkdir -p "$work/legs/noboot"
    echo '{"id": "tv-30", "name": "Android TV 11", "profile": "tv_1080p", "api": "30", "target": "android-tv"}' > "$work/noboot.json"
    exits 1 dm verdict "$work/legs/noboot" --leg-json "$work/noboot.json"
    grep -q 'did not boot' "$work/dm-output" || fail "device-matrix.py verdict didn't say the leg never booted"
    echo "The emulator quit before it booted." > "$work/legs/noboot/emulator-quit.txt"
    exits 1 dm verdict "$work/legs/noboot" --leg-json "$work/noboot.json"
    grep -q '::error title=tv-30::NO BOOT: The emulator quit before it booted.' "$work/dm-output" ||
        fail "device-matrix.py verdict didn't say the emulator quit"
    rm "$work/legs/noboot/emulator-quit.txt"
    table=$(dm summary "$work/legs")
    for want in "| Small phone, Android 15 | small_phone, 1600x2560 at 320 dpi | API 35 google_apis | 2 / 0 / 0 |" \
                "**Galaxy Z Fold shape, Android 16**: CRASH" "4 of 8 legs passed or were skipped."; do
        grep -qF -- "$want" <<< "$table" || { printf '%s\n' "$table" >&2; fail "device-matrix.py summary has no \"$want\""; }
    done
fi
if grep -q uninstall "$work/all-calls"; then fail "device-test.sh or device-run.sh uninstalled something"; fi
rm -rf "$work"
ok "device-run.sh runs a leg's passes on emulators only, and reports them"

# 13. check-publish.sh, in a made-up repo: an upstream commit brought in with git cherry-pick -x keeps
# its author, which passes only when the commit its last line names is on an upstream ref and has the
# same author and date, and the committer is still the publishing identity in UTC. What it adds is
# still checked, and the output never shows what matched. Its default list of upstream remotes has
# every fork Starwire takes commits from.
begin
work="$(mktemp -d)"
repo="$work/repo"
git init -q "$repo"
me="Pub Lisher <publisher@example.com>"
pub="Pub Lisher|publisher@example.com|1700000100 +0000"
up="Up Stream|upstream@example.org|1700000000 -0700"
echo 'planted-private-word' > "$work/patterns.txt"
mk() {  # parent (or ""), author and committer as "name|email|raw date", a line for notes.txt, message
    local a c blob tree
    IFS='|' read -r -a a <<< "$2"
    IFS='|' read -r -a c <<< "$3"
    blob=$(printf '%s\n' "$4" | git -C "$repo" hash-object -w --stdin)
    tree=$(printf '100644 blob %s\tnotes.txt\n' "$blob" | git -C "$repo" mktree)
    GIT_AUTHOR_NAME=${a[0]} GIT_AUTHOR_EMAIL=${a[1]} GIT_AUTHOR_DATE=${a[2]} \
    GIT_COMMITTER_NAME=${c[0]} GIT_COMMITTER_EMAIL=${c[1]} GIT_COMMITTER_DATE=${c[2]} \
        git -C "$repo" -c commit.gpgsign=false commit-tree "$tree" ${1:+-p "$1"} -m "$5"
}
upstream=$(mk "" "$up" "$up" "an upstream change" "Change something upstream")
git -C "$repo" update-ref refs/remotes/moonlightx/main "$upstream"
base=$(mk "" "$pub" "$pub" "the public tip" "Start")
git -C "$repo" update-ref refs/heads/main "$base"
# The upstream author and date on a commit that isn't upstream's
lookalike=$(mk "" "$up" "$up" "not upstream" "Only this repo has it")
picked="(cherry picked from commit $upstream)"
publish() {  # expected exit code, a line the output must have (COMMIT for the new commit), then mk's
             # arguments after the parent
    local expected=$1 want=$2 tip status
    shift 2
    tip=$(mk "$base" "$@")
    want=${want//COMMIT/${tip:0:10}}
    set +e
    published=$(cd "$repo" && PUBLISH_IDENTITY="$me" UPSTREAM_REMOTES=moonlightx \
        bash "$HERE/check-publish.sh" main "$tip" "$work/patterns.txt" 2>&1)
    status=$?
    set -e
    if [ $status -ne "$expected" ] || ! grep -qxF -- "$want" <<< "$published"; then
        printf '%s\n' "$published" >&2
        fail "check-publish.sh on \"${4%%$'\n'*}\": exit $status (expected $expected), or no line \"$want\""
    fi
}
publish 0 "clean" "$up" "$pub" "the upstream change" "Change something upstream

$picked"
publish 1 "identity: COMMIT author is not $me, and its cherry-pick line names no upstream commit" \
    "$up" "$pub" "x" "A cherry-pick line naming a commit that isn't upstream's

(cherry picked from commit $lookalike)"
publish 1 "identity: COMMIT author is not $me, and its cherry-pick line names no upstream commit" \
    "$up" "$pub" "x" "A cherry-pick line naming a commit this repo doesn't have

(cherry picked from commit 0123456789abcdef0123456789abcdef01234567)"
for author in "Up Streamer|upstream@example.org|1700000000 -0700" "Up Stream|someone@example.org|1700000000 -0700" \
              "Up Stream|upstream@example.org|1700000001 -0700" "Up Stream|upstream@example.org|1700000000 +0000"; do
    publish 1 "identity: COMMIT author is not $me, nor the author and date of the upstream commit it names" \
        "$author" "$pub" "x" "A cherry-pick with a changed author or date

$picked"
done
publish 1 "identity: COMMIT committer is not $me" "$up" "Some One|someone@example.org|1700000100 +0000" "x" \
    "A cherry-pick committed by someone else

$picked"
if grep -q 'author' <<< "$published"; then fail "check-publish.sh didn't accept the author of a real cherry-pick"; fi
publish 1 "timezone: COMMIT committer date is not +0000" "$up" "Pub Lisher|publisher@example.com|1700000100 -0700" \
    "x" "A cherry-pick committed outside UTC

$picked"
publish 1 "identity: COMMIT author is not $me" "$up" "$pub" "x" "A cherry-pick line that isn't the last line

$picked

More text"
publish 1 "personal: COMMIT notes.txt:1 [private pattern on line 1]" "$up" "$pub" "a planted-private-word here" \
    "A cherry-pick that adds a private word

$picked"
if grep -q 'planted-private-word' <<< "$published"; then fail "check-publish.sh showed the private word it found"; fi
if grep -q 'identity\|timezone' <<< "$published"; then fail "check-publish.sh failed a real cherry-pick's identity"; fi
# With UPSTREAM_REMOTES unset, a pick from any fork Starwire takes commits from counts as upstream:
# Moonlight X, Artemide, Turnip, Nightjar and Moonlight V+ (vplus). A fork not on that list doesn't.
for remote in moonlightx artemide turnip nightjar vplus elsewhere; do
    fork=$(mk "" "$up" "$up" "a change in $remote" "Change something in $remote")
    git -C "$repo" update-ref "refs/remotes/$remote/fork-main" "$fork"
    tip=$(mk "$base" "$up" "$pub" "the change from $remote" "Change something in $remote

(cherry picked from commit $fork)")
    expected=0 want="clean"
    if [ "$remote" = elsewhere ]; then
        expected=1 want="identity: ${tip:0:10} author is not $me, and its cherry-pick line names no upstream commit"
    fi
    set +e
    published=$(cd "$repo" && unset UPSTREAM_REMOTES && PUBLISH_IDENTITY="$me" \
        bash "$HERE/check-publish.sh" main "$tip" "$work/patterns.txt" 2>&1)
    status=$?
    set -e
    if [ $status -ne $expected ] || ! grep -qxF -- "$want" <<< "$published"; then
        printf '%s\n' "$published" >&2
        fail "check-publish.sh's default upstream remotes, on a pick from $remote: exit $status (expected $expected), or no line \"$want\""
    fi
done
rm -rf "$work"
ok "check-publish.sh accepts real upstream cherry-picks, from every fork on its default list, and nothing that only looks like one"

# 14. check-commits.sh sorts its planted commit messages, branch names and commits (in a throwaway
# repo of its own) the way CONTRIBUTING.md's rules say
begin
set +e
commits_out=$(bash "$HERE/check-commits.sh" --self-test 2>&1)
status=$?
set -e
if [ $status -ne 0 ]; then
    printf '%s\n' "$commits_out" | grep -v '^ok ' >&2 || true
    fail "check-commits.sh --self-test exited $status"
fi
grep '^skipped: ' <<< "$commits_out" || true
ok "check-commits.sh tells good commit messages and branch names from bad ones"

# 15. check-version.sh sorts its planted tag names, versions and releases (in a throwaway repo of
# its own) the way SemVer 2.0.0 and RELEASING.md's version rules say
begin
set +e
version_out=$(bash "$HERE/check-version.sh" --self-test 2>&1)
status=$?
set -e
if [ $status -ne 0 ]; then
    printf '%s\n' "$version_out" | grep -v '^ok ' >&2 || true
    fail "check-version.sh --self-test exited $status"
fi
grep '^skipped: ' <<< "$version_out" || true
ok "check-version.sh tells good release tags and version bumps from bad ones"

# 16. RELEASING.md says how to add and fetch, without tags, every fork remote check-publish.sh
# counts as upstream by default. A clone without one fails the check on that fork's picks, and
# RELEASING.md once named only three of the five.
begin
default_remotes=$(sed -n 's/^UPSTREAM=\${UPSTREAM_REMOTES:-\(.*\)}$/\1/p' "$HERE/check-publish.sh")
releasing=$(tr -d '\r' < "$ROOT/docs/dev/RELEASING.md")
fetch_loop=$(grep -E '^for remote in .*tagOpt --no-tags.*git fetch' <<< "$releasing" || true)
if [ -z "$default_remotes" ]; then
    fail "couldn't find check-publish.sh's default UPSTREAM_REMOTES"
fi
for remote in $default_remotes; do
    if ! grep -qE "^git remote add $remote https://github\.com/[^ ]+\.git$" <<< "$releasing"; then
        fail "RELEASING.md doesn't say how to add the $remote remote (git remote add $remote https://github.com/...)"
    fi
    if ! sed 's/;.*//' <<< "$fetch_loop" | grep -qw -- "$remote"; then
        fail "RELEASING.md's fetch loop doesn't fetch $remote without tags"
    fi
done
ok "RELEASING.md adds and fetches every fork remote check-publish.sh counts as upstream"

# 17. The USB tunnel's shared files are what their scripts in vectors/ write
# (docs/dev/usb-protocol.md, "Test vectors"), so the PC helper's side and later reviewers can make
# them again. The vectors need OpenSSL 3.2 or later (deterministic ECDSA), which not every runner
# has, so without it that half is skipped with a note.
begin
vectors_tmp=$(mktemp -d)
bash "$HERE/vectors/make-swut-v1-policy.sh" "$vectors_tmp/policy.txt"
for copy in windows/tests/vectors/swut-v1-policy.txt app/src/test/resources/swut-v1-policy.txt; do
    cmp -s "$vectors_tmp/policy.txt" "$copy" || fail "make-swut-v1-policy.sh doesn't write $copy"
done
openssl_version=$(openssl version 2>/dev/null || true)
if [[ "$openssl_version" =~ ^OpenSSL\ ([0-9]+)\.([0-9]+) ]] &&
        { [ "${BASH_REMATCH[1]}" -gt 3 ] || { [ "${BASH_REMATCH[1]}" -eq 3 ] && [ "${BASH_REMATCH[2]}" -ge 2 ]; }; }; then
    bash "$HERE/vectors/make-swut-v1-vectors.sh" "$vectors_tmp/swut.txt" "$vectors_tmp/work"
    for copy in windows/tests/vectors/swut-v1-vectors.txt app/src/test/resources/swut-v1-vectors.txt; do
        cmp -s "$vectors_tmp/swut.txt" "$copy" || fail "make-swut-v1-vectors.sh doesn't write $copy"
    done
else
    echo "skipped: remaking swut-v1-vectors.txt (needs OpenSSL 3.2 or later, found: ${openssl_version:-none})"
fi
rm -rf "$vectors_tmp"
ok "the USB tunnel's vectors and device rules' cases are what their scripts write"

# 18. check-publish.sh fails closed: when a step can't run, it says so, exits 2 and never says
# "clean". With C: full, its awk couldn't write a temporary file and it said "clean". Here: a
# temporary folder it can't write in, an awk that fails, a git that fails at the last step, and
# no patterns file. The commit it checks is clean, so only the failure can stop it.
begin
work="$(mktemp -d)"
repo="$work/repo"
git init -q "$repo"
base=$(mk "" "$pub" "$pub" "the public tip" "Start")
git -C "$repo" update-ref refs/heads/main "$base"
tip=$(mk "$base" "$pub" "$pub" "a new line" "Add a line")
echo 'planted-private-word' > "$work/patterns.txt"
: > "$work/a-file"
mkdir "$work/mktemp" "$work/awk" "$work/git"
# A mktemp that names a folder nothing can be written in, as on a full disk
printf '%s\n' '#!/usr/bin/env bash' "echo '$work/a-file/tmp.made-up'" > "$work/mktemp/mktemp"
printf '%s\n' '#!/usr/bin/env bash' 'echo "awk: fatal: made up for the test" >&2' 'exit 2' > "$work/awk/awk"
printf '%s\n' '#!/usr/bin/env bash' \
    'if [ "$1" = grep ]; then echo "fatal: made up for the test" >&2; exit 128; fi' \
    "exec '$(command -v git)' \"\$@\"" > "$work/git/git"
chmod +x "$work/mktemp/mktemp" "$work/awk/awk" "$work/git/git"
publish_with() {  # expected exit code, what's wrong, the patterns file, then NAME=value settings
    local expected=$1 what=$2 patterns=$3 status
    shift 3
    set +e
    published=$(cd "$repo" && env "$@" PUBLISH_IDENTITY="$me" UPSTREAM_REMOTES=moonlightx \
        bash "$HERE/check-publish.sh" main "$tip" "$patterns" 2>&1)
    status=$?
    set -e
    if [ "$expected" = 0 ] && { [ $status -ne 0 ] || ! grep -qx 'clean' <<< "$published"; }; then
        printf '%s\n' "$published" >&2
        fail "check-publish.sh on a clean commit: exit $status (expected 0 and \"clean\")"
    elif [ "$expected" = 2 ] && { [ $status -ne 2 ] || grep -qx 'clean' <<< "$published" ||
                                  ! grep -q '^error: ' <<< "$published"; }; then
        printf '%s\n' "$published" >&2
        fail "check-publish.sh with $what: exit $status (expected 2, an error and no \"clean\")"
    fi
}
publish_with 0 "nothing wrong" "$work/patterns.txt"
publish_with 2 "TMPDIR naming a file" "$work/patterns.txt" TMPDIR="$work/a-file"
publish_with 2 "a temporary folder it can't write in" "$work/patterns.txt" PATH="$work/mktemp:$PATH"
publish_with 2 "an awk that fails" "$work/patterns.txt" PATH="$work/awk:$PATH"
publish_with 2 "a git that fails at the placeholder search" "$work/patterns.txt" PATH="$work/git:$PATH"
publish_with 2 "no patterns file" "$work/no-such-patterns.txt"
rm -rf "$work"
ok "check-publish.sh stops with an error, never \"clean\", when a step fails or the patterns file is missing"

# 19. make-windows-zip.sh makes the Windows zip, for the Release workflow and, on every push, the
# Build workflow. The release once zipped a fixed list of the microphone's files, which left out
# the USB helper's, although its setup says to run it from the unzipped folder. The list has
# both; a missing file, or a file a setup script takes from its folder that isn't on the list,
# stops it; and the Release workflow makes its zip only this way.
begin
mic="setup-microphone.cmd setup-microphone.ps1 StarwireMicReceiver.cs SecureMic.cs starwire-mic.ico README.md"
usb="setup-usb.cmd setup-usb.ps1 StarwireUsbHelper.cs UsbTunnel.cs UsbRelay.cs UsbTunnelCodec.cs UsbDeviceRules.cs USB-HELPER.md"
want=$(printf '%s\n' $mic $usb | LC_ALL=C sort)
set +e
listed=$(bash "$HERE/make-windows-zip.sh" --list 2>&1)
status=$?
set -e
if [ $status -ne 0 ] || [ "$(printf '%s\n' "$listed" | LC_ALL=C sort)" != "$want" ]; then
    printf '%s\n' "$listed" >&2
    fail "make-windows-zip.sh --list (exit $status) isn't the microphone's files and the USB helper's"
fi
set +e
out=$(bash "$HERE/make-windows-zip.sh" --check 2>&1)
status=$?
set -e
if [ $status -ne 0 ]; then
    printf '%s\n' "$out" >&2
    fail "make-windows-zip.sh --check fails on windows/ (exit $status)"
fi
work="$(mktemp -d)"
copy_windows() {  # folder: the zip's files, copied from windows/
    local f
    mkdir -p "$1"
    for f in $want; do cp "windows/$f" "$1/"; done
}
zip_fails() {  # what's wrong, a file the error must name, the folder to take the files from
    local status out
    set +e
    out=$(WINDOWS_DIR=$3 bash "$HERE/make-windows-zip.sh" --check 2>&1)
    status=$?
    set -e
    if [ $status -ne 1 ] || ! grep -qF -- "$2" <<< "$out"; then
        printf '%s\n' "$out" >&2
        fail "make-windows-zip.sh --check with $1: exit $status (expected 1, naming $2)"
    fi
}
copy_windows "$work/missing"
rm "$work/missing/UsbRelay.cs"
zip_fails "UsbRelay.cs missing" UsbRelay.cs "$work/missing"
copy_windows "$work/new-source"
sed -i "s/'UsbDeviceRules.cs')/'UsbDeviceRules.cs', 'UsbExtra.cs')/" "$work/new-source/setup-usb.ps1"
echo "// made up for the test" > "$work/new-source/UsbExtra.cs"
zip_fails "a source setup-usb.ps1 builds from that isn't on the list" UsbExtra.cs "$work/new-source"
copy_windows "$work/new-icon"
sed -i "s/Join-Path \$here 'starwire-mic.ico'/Join-Path \$here 'starwire-mic-2.ico'/" "$work/new-icon/setup-microphone.ps1"
cp windows/starwire-mic.ico "$work/new-icon/starwire-mic-2.ico"
zip_fails "a file setup-microphone.ps1 takes that isn't on the list" starwire-mic-2.ico "$work/new-icon"
copy_windows "$work/no-sources"
sed -i 's/^\$sourceNames = /$helperSources = /' "$work/no-sources/setup-usb.ps1"
zip_fails "no list of sources it can find in setup-usb.ps1" setup-usb.ps1 "$work/no-sources"
if command -v zip >/dev/null 2>&1 && command -v unzip >/dev/null 2>&1; then
    # Over an old file of the same name, which zip would otherwise add to
    mkdir "$work/out"
    echo "an old zip" > "$work/out/Starwire-Microphone-for-Windows.zip"
    if bash "$HERE/make-windows-zip.sh" "$work/out" > /dev/null; then
        got=$(unzip -Z1 "$work/out/Starwire-Microphone-for-Windows.zip" | LC_ALL=C sort)
        [ "$got" = "$want" ] || fail "the Windows zip holds $(echo $got), not the list"
    else
        fail "make-windows-zip.sh couldn't make the zip from windows/"
    fi
    set +e
    WINDOWS_DIR=$work/missing bash "$HERE/make-windows-zip.sh" "$work/out-missing" > /dev/null 2>&1
    status=$?
    set -e
    if [ $status -eq 0 ] || [ -e "$work/out-missing/Starwire-Microphone-for-Windows.zip" ]; then
        fail "make-windows-zip.sh made a zip without UsbRelay.cs (exit $status)"
    fi
else
    echo "skipped: making the Windows zip (needs zip and unzip)"
fi
for workflow in release build; do
    grep -q 'bash .github/tools/make-windows-zip.sh ' ".github/workflows/$workflow.yml" ||
        fail "$workflow.yml doesn't make the Windows zip with make-windows-zip.sh"
done
if grep -nE '(^|[^A-Za-z0-9_.-])zip +-' .github/workflows/release.yml; then
    fail "release.yml runs zip itself; its Windows zip comes from make-windows-zip.sh"
fi
grep -q 'ZIP=Starwire-Microphone-for-Windows.zip' .github/workflows/release.yml ||
    fail "release.yml doesn't publish Starwire-Microphone-for-Windows.zip"
rm -rf "$work"
ok "make-windows-zip.sh ships the microphone's and the USB helper's files, and the workflows make the zip with it"

# 20. verify.sh sums, in a made-up repo: every binary file in git must be in SHA256SUMS, and text
# isn't binary whatever its line endings. The scan took git's word, and git calls text with a
# lone CR binary, so sdkmanager's output in fixtures/ (CR, CRLF and LF line endings) failed the
# Prebuilt files check as an unlisted binary. A file with a NUL byte, one that isn't UTF-8 and
# one that's mostly control characters are still binary.
begin
py=""
for candidate in python3 python py; do
    if "$candidate" -c 'import sys; sys.exit(sys.version_info < (3, 6))' > /dev/null 2>&1; then py=$candidate; break; fi
done
if [ -z "$py" ]; then
    if [ -n "${CI:-}" ]; then fail "no Python 3 for verify.sh sums"; else echo "skipped: verify.sh sums in a made-up repo (no Python 3)"; fi
else
    work="$(mktemp -d)"
    repo="$work/repo"
    mkdir -p "$repo/.github/tools/prebuilts"
    cp "$HERE/prebuilts/verify.sh" "$repo/.github/tools/prebuilts/verify.sh"
    git init -q "$repo"
    cp "$HERE/fixtures/sdkmanager-list-22.txt" "$repo/sdkmanager-list.txt"
    # Like a progress bar: CRLF, then a lone CR before each update, then LF
    printf 'Loading package information...\r\n[=====     ] 50%%\r[==========] 100%%\nInstalled packages:\r\n' > "$repo/progress.txt"
    printf '\177ELF\2\1\1\0\0\0' > "$repo/listed.so"
    printf '%s  listed.so\n' "$(sha256sum < "$repo/listed.so" | cut -c1-64)" > "$repo/.github/tools/prebuilts/SHA256SUMS"
    sums() {  # runs verify.sh sums on the made-up repo, and sets status and out
        git -C "$repo" -c core.autocrlf=false add -A
        set +e
        out=$(PYTHON=$py sh "$repo/.github/tools/prebuilts/verify.sh" --work "$work/verify" sums 2>&1)
        status=$?
        set -e
    }
    sums
    if [ $status -ne 0 ] || ! grep -qE '^MATCH +SHA256SUMS +1 files$' <<< "$out"; then
        printf '%s\n' "$out" >&2
        fail "verify.sh sums on text with CR, CRLF and LF line endings: exit $status (expected 0, and SHA256SUMS a MATCH)"
    fi
    # (Not nul.txt: Windows keeps that name for its NUL device)
    for planted in 'zero-byte.txt|a NUL byte|one\0two\n' 'latin1.txt|text that is not UTF-8|caf\351\n' \
            'control.txt|mostly control characters|ab\1\2\3\4\5\6\n'; do
        IFS='|' read -r name what content <<< "$planted"
        # shellcheck disable=SC2059
        printf "$content" > "$repo/$name"
        sums
        if [ $status -ne 1 ] || ! grep -qE "^DIFFERENT +${name//./\\.} +binary file not listed in SHA256SUMS\$" <<< "$out"; then
            printf '%s\n' "$out" >&2
            fail "verify.sh sums on $name ($what): exit $status (expected 1, and $name an unlisted binary)"
        fi
        git -C "$repo" rm -q --cached "$name"
        rm "$repo/$name"
    done
    rm -rf "$work"
fi
ok "verify.sh sums counts text with any line endings as text, and still finds unlisted binary files"

# 21. The Upstream changes workflow runs only on the public repo, where its weekly issue belongs.
# On the private copy every scheduled run failed at the checkout ("repository not found").
begin
public_only() {  # prints each job in a workflow, then "public" if a job-level if keeps it to the public repo
    tr -d '\r' < "$1" | awk -v guard="    if: github.repository == 'SicarioGaming/Starwire'" '
        /^jobs:/ { injobs = 1; next }
        injobs && /^[^[:space:]#]/ { injobs = 0 }
        !injobs { next }
        /^  [A-Za-z0-9_-]+:[[:space:]]*$/ {
            if (job != "") print job, where
            job = $1; sub(/:$/, "", job); where = "everywhere"; next
        }
        job != "" { sub(/[[:space:]]+$/, ""); if ($0 == guard) where = "public" }
        END { if (job != "") print job, where }'
}
upstream_jobs=$(public_only .github/workflows/upstream.yml)
if [ -z "$upstream_jobs" ]; then
    fail "found no jobs in upstream.yml"
else
    while read -r job where; do
        [ "$where" = public ] ||
            fail "upstream.yml: the $job job runs on every copy of the repo, not only with if: github.repository == 'SicarioGaming/Starwire'"
    done <<< "$upstream_jobs"
fi
work="$(mktemp -d)"
printf '%s\n' 'on: push' 'jobs:' '  guarded:' "    if: github.repository == 'SicarioGaming/Starwire'" \
    '    runs-on: x' '  step-only:' '    runs-on: x' '    steps:' \
    "      - if: github.repository == 'SicarioGaming/Starwire'" '        run: true' > "$work/guards.yml"
planted_jobs=$(public_only "$work/guards.yml" | tr '\n' ' ')
[ "$planted_jobs" = "guarded public step-only everywhere " ] ||
    fail "the public repo check misread a planted workflow: $planted_jobs"
rm -rf "$work"
ok "the Upstream changes workflow runs only on the public repo"

# 22. upstream/check.sh, against made-up upstream repos on disk: it lists what's new, and no git it
# runs is still going after it ends. git fetch ends by starting "git maintenance run --auto",
# which on Linux goes on in the background writing commit-graph files. On 9 Nov 2026 that was
# still writing in the temporary folder when check.sh's EXIT trap removed it, so rm failed ("cannot
# remove .../repo/objects: Directory not empty") and the weekly run failed with it. Maintenance and
# gc are what git leaves running, so this checks, from git's trace2 log, that check.sh starts
# neither. Waiting for the race wouldn't be deterministic, and on Windows maintenance can't go into
# the background, so the race never shows there.
begin
work="$(mktemp -d)"
host_path() {  # Git Bash's /c/x as C:/x, for the paths git reads from its config and environment
    if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else printf '%s\n' "$1"; fi
}
mkdir -p "$work/tool" "$work/tmp"
cp "$HERE/upstream/check.sh" "$work/tool/check.sh"
git init -q --bare "$work/up/test/one.git"
# 120 commits, a day apart: over the 100 new commits that make maintenance write a commit-graph
for i in $(seq 1 120); do
    printf 'commit refs/heads/main\ncommitter Up Stream <upstream@example.org> %d +0000\ndata <<EOF\nChange %d\nEOF\n\n' \
        $((1700000000 + i * 86400)) "$i"
done | git -C "$work/up/test/one.git" fast-import --quiet
reviewed=$(git -C "$work/up/test/one.git" rev-parse main~2)
# One source with two new commits, one whose reviewed commit isn't there, and one that can't be fetched
printf '%s\n' "one   test/one   main  $reviewed  port" \
    "gone  test/one   main  0123456789abcdef0123456789abcdef01234567  ideas" \
    "none  test/none  main  $reviewed  port" > "$work/tool/sources.txt"
# Only this config, which sends https://github.com/ to the made-up repos (with filters allowed, as
# GitHub does), so a maintenance.auto in the user's own config can't hide the problem. Git may
# only read local paths, so a URL the config misses fails here instead of going to the network.
git config --file "$work/gitconfig" "url.$(host_path "$work/up")/.insteadOf" https://github.com/
git config --file "$work/gitconfig" uploadpack.allowFilter true
set +e
upstream_report=$(TMPDIR="$work/tmp" GIT_ALLOW_PROTOCOL=file GIT_CONFIG_NOSYSTEM=1 \
    GIT_CONFIG_GLOBAL="$(host_path "$work/gitconfig")" GIT_TRACE2_EVENT="$(host_path "$work/trace.json")" \
    bash "$work/tool/check.sh" 2> "$work/stderr")
status=$?
set -e
if [ $status -ne 0 ] || [ -s "$work/stderr" ]; then
    cat "$work/stderr" >&2
    fail "upstream/check.sh exited $status (expected 0, and nothing on stderr)"
fi
for want in "## one: 2 new commit(s) (port)" "- Change 120 (Up Stream, 2024-03-13, \`$(git -C "$work/up/test/one.git" rev-parse --short=7 main)\`)" \
            "- Change 119 (Up Stream, 2024-03-12, \`$(git -C "$work/up/test/one.git" rev-parse --short=7 main~1)\`)" \
            "The reviewed commit \`01234567\` is no longer on \`main\` (history was rewritten)." \
            "## none (test/none)" "Couldn't fetch \`main\`."; do
    grep -qxF -- "$want" <<< "$upstream_report" || { printf '%s\n' "$upstream_report" >&2; fail "upstream/check.sh's report has no line \"$want\""; }
done
started=$(grep '"event":"start"' "$work/trace.json" 2> /dev/null || true)
if ! grep -qE '"argv":\[[^]]*"fetch"' <<< "$started"; then
    fail "git's trace2 log of upstream/check.sh has no fetch in it"
elif grep -E '"argv":\[[^]]*"(maintenance|gc)"' <<< "$started" >&2; then
    fail "upstream/check.sh started git maintenance or gc, which can still be writing in its temporary folder when it removes it"
fi
if [ -n "$(ls -A "$work/tmp")" ]; then fail "upstream/check.sh left its temporary folder behind: $(ls -A "$work/tmp")"; fi
rm -rf "$work"
ok "upstream/check.sh lists new upstream commits and leaves nothing running in its temporary folder"

if [ $failures -ne 0 ]; then
    echo "$failures problem(s)" >&2
    exit 1
fi
echo "All tool checks passed"
