#!/usr/bin/env bash
# Builds and runs the Linux microphone receiver's tests (GPL-3.0, part of Starwire):
#
# 1. SecureMicTests: the Windows receiver's tests of SecureMic.cs (the format, the pairing, the
#    endpoint's rules, the paired devices file, a seeded fuzz), built for Linux and checked against
#    the vectors the Android app shares, byte for byte.
# 2. LinuxTests: the Linux receiver's own parts, with a fake clock.
# 3. End to end: a real receiver on this PC only (a non-default port, its own pairings file and
#    microphone name, so an installed receiver isn't touched) and tests/FakePhone.cs, which pairs
#    with the code it prints and streams a tone. The receiver must play exactly what was sent and
#    refuse the rest. With PipeWire running, the tone is recorded back from the microphone too;
#    without it that part is skipped, and says so. A `starwire-mic watch` runs all the while, as the
#    app (app/) does, and must see the device pair and its audio come.
# 4. The app builds, with its NuGet packages checked against app/packages.lock.json.
#
#   linux/tests/run-tests.sh [out-dir]
#
# Exits with 1 if anything fails. Needs the .NET SDK (8 or later; the projects target 10).

set -uo pipefail
tests=$(cd "$(dirname "$0")" && pwd)
linux=$(dirname "$tests")
repo=$(dirname "$linux")
out=${1:-$(mktemp -d -t starwire-mic-tests.XXXXXX)}
mkdir -p "$out"
failed=()

step() { printf '\n\033[36m== %s\033[0m\n' "$1"; }

build() {
    if ! dotnet build "$1" -c Release -nologo -v q >"$out/build-$(basename "$1" .csproj).txt" 2>&1; then
        grep -E 'error|warn' "$out/build-$(basename "$1" .csproj).txt" | sort -u
        failed+=("building $(basename "$1" .csproj)")
        return 1
    fi
}

bin() { echo "$linux/bin/$1/Release/net10.0/$1"; }

step 'Building'
build "$linux/StarwireMic.csproj"
build "$tests/SecureMicTests.csproj"
build "$tests/LinuxTests.csproj"
build "$tests/FakePhone.csproj"
# The app's packages must be the ones the lock file names, byte for byte
export AVALONIA_TELEMETRY_OPTOUT=1 DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1
if ! dotnet build "$linux/app/StarwireMicApp.csproj" -c Release -nologo -v q -p:RestoreLockedMode=true >"$out/build-StarwireMicApp.txt" 2>&1; then
    grep -E 'error|warn' "$out/build-StarwireMicApp.txt" | sort -u
    failed+=("building the app")
fi
receiver=$(bin StarwireMic | sed 's|StarwireMic$|starwire-mic|')
[ ${#failed[@]} -eq 0 ] && echo 'Built.'

# Only the failures and the total; every check's line is in the output folder
run() {
    local name=$1; shift
    step "$name"
    "$@" >"$out/$name.txt" 2>&1
    local code=$?
    grep -v '^PASS ' "$out/$name.txt"
    [ $code -eq 0 ] || failed+=("$name")
}

[ -x "$(bin SecureMicTests)" ] && run SecureMicTests "$(bin SecureMicTests)" "$repo/windows/tests/vectors/swmc-v1-vectors.txt"
[ -x "$(bin LinuxTests)" ] && run LinuxTests "$(bin LinuxTests)"

step 'End to end: a receiver on this PC and a test phone'
if [ -x "$receiver" ] && [ -x "$(bin FakePhone)" ]; then
    port=6993
    pairings="$out/e2e/mic-pairings.dat"
    node="starwire-mic-e2e-$$"
    rm -rf "$out/e2e"
    mkdir -p "$out/e2e"
    "$receiver" --loopback --port $port --pairings-file "$pairings" --node-name "$node" --pair --no-notify \
        --exit-after-idle 2 >"$out/receiver.txt" 2>&1 &
    rpid=$!
    code=
    for _ in $(seq 50); do
        code=$(sed -n 's/^ *Pairing code: //p' "$out/receiver.txt")
        [ -n "$code" ] && break
        sleep 0.2
    done
    if [ -z "$code" ]; then
        cat "$out/receiver.txt"
        failed+=('the receiver printing a pairing code')
        kill $rpid 2>/dev/null
    else
        # What the app sees, all the while
        "$receiver" --pairings-file "$pairings" watch >"$out/watch.txt" 2>&1 &
        wpid=$!
        record=()
        if pw-cli info 0 >/dev/null 2>&1 && grep -q 'Output: PipeWire source' "$out/receiver.txt"; then
            record=("$node")
        else
            echo 'SKIP recording the microphone back: PipeWire isn'\''t running here'
        fi
        # 750 packets: 4 seconds
        "$(bin FakePhone)" $port "$code" 750 2000 "${record[@]}" >"$out/phone.txt" 2>&1
        phone=$?
        grep -v '^PASS ' "$out/phone.txt"
        [ $phone -eq 0 ] || failed+=('the test phone')
        wait $rpid
        # The receiver has stopped, so the watch ends too
        for _ in $(seq 20); do kill -0 $wpid 2>/dev/null || break; sleep 0.1; done
        kill $wpid 2>/dev/null
        summary=$(grep '^Summary:' "$out/receiver.txt")
        sent=$(sed -n 's/^SENT played=\([0-9]*\) replayed=\([0-9]*\) pcm=\([0-9a-f]*\)$/\1 \2 \3/p' "$out/phone.txt")
        read -r played replayed pcm <<<"$sent"
        echo "Receiver: ${summary#Summary: }"
        check() {
            if [ "$2" = yes ]; then echo "PASS $1"; else echo "FAIL $1"; failed+=("end to end: $1"); fi
        }
        has() { [[ "$summary" == *"$1"* ]] && echo yes || echo no; }
        check "it played every packet the phone sent ($played)" "$(has "played $played,")"
        check "the audio it played is the audio sent, byte for byte" "$(has "PCM fnv1a64 $pcm")"
        check "it refused the replayed packet" "$(has "replayed or late $replayed,")"
        check "it counted the unpaired device's HELLO" "$(has "unpaired 1,")"
        check "it counted the wrong code" "$(has "wrong codes 1,")"
        check "nothing forged, malformed or failed" "$( [[ "$summary" == *"forged 0,"* && "$summary" == *"malformed 0,"* && "$summary" == *"errors 0,"* ]] && echo yes || echo no)"
        mode=$(stat -c %a "$pairings" 2>/dev/null)
        check "the paired devices file is only this user's (mode $mode)" "$( [ "$mode" = 600 ] && echo yes || echo no)"
        tab=$'\t'
        seen() { grep -qF -- "$1" "$out/watch.txt" && echo yes || echo no; }
        check "watch said the port, and that the microphone is ready" "$( [ "$(seen "port${tab}$port")" = yes ] && [ "$(seen "mic${tab}ready")" = yes ] && echo yes || echo no)"
        check "watch saw the device pair" "$(seen "devices${tab}1${tab}")"
        check "watch saw its audio come" "$(seen "status${tab}Receiving the microphone from Fake phone (encrypted)")"
        check "the microphone went away when it stopped" "$(pw-cli ls Node 2>/dev/null | grep -q "$node" && echo no || echo yes)"
    fi
fi

echo
if [ ${#failed[@]} -gt 0 ]; then
    printf '\033[31mFailed: %s\033[0m\n' "$(IFS=,; echo "${failed[*]}" | sed 's/,/, /g')"
    echo "Every check's line is in $out"
    exit 1
fi
printf '\033[32mAll passed.\033[0m (Every check'\''s line is in %s)\n' "$out"
exit 0
