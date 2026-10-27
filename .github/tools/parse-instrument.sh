#!/usr/bin/env bash
# Reads the output of `am instrument -w -r` and says how the run went: one line per test, then a
# total line, always last. am exits 0 whether or not the tests pass, so this is the verdict.
#
#   .github/tools/parse-instrument.sh INSTRUMENT_OUTPUT
#
#   PASS com.limelight.StringsTest#theAppIsCalledStarwireInEveryLanguage
#   SKIP com.limelight.smoke.StreamSmokeTest#... (the reason the test gave)
#   FAIL com.limelight.SomeTest#someCase: java.lang.AssertionError: what went wrong
#   RUN PROBLEM: Process crashed.
#   40 passed, 1 failed, 2 skipped, run problem
#
# A test the watchdog (StuckTestWatchdog, which device-test.sh adds to each run) caught hanging
# fails with the watchdog's words, even if it passes after the watchdog stepped in.
#
# Exit codes: 0 every test passed or skipped, 1 a test failed, 2 the run itself went wrong (the
# process crashed, it didn't finish, or no test ran at all).

set -uo pipefail

if [ $# -ne 1 ] || [ ! -f "$1" ]; then
    sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
fi

awk '
    { sub(/\r$/, "") }
    /^INSTRUMENTATION_STATUS: class=/ { cls = substr($0, index($0, "=") + 1) }
    /^INSTRUMENTATION_STATUS: test=/ { test = substr($0, index($0, "=") + 1) }
    /^INSTRUMENTATION_STATUS: stuck=/ { stuckmsg = substr($0, index($0, "=") + 1) }
    # The first line of the stack says why a test failed or was skipped
    /^INSTRUMENTATION_STATUS: stack=/ {
        reason = substr($0, index($0, "=") + 1)
        sub(/^[A-Za-z0-9_.$]*AssumptionViolatedException: /, "", reason)
    }
    /^INSTRUMENTATION_STATUS_CODE: / {
        code = $2
        name = cls "#" test
        if (stuckmsg != "") { stuck[name] = stuckmsg }
        if (code == 1) {
            running = name
        }
        else {
            running = ""
            if (name in stuck) {
                failed++
                print "FAIL " name ": " stuck[name] (code != 0 && code != -3 && code != -4 && reason != "" ? "; then " reason : "")
            }
            else if (code == 0) { passed++; print "PASS " name }
            else if (code == -3 || code == -4) {
                skipped++
                print "SKIP " name (reason != "" ? " (" reason ")" : "")
            }
            else { failed++; print "FAIL " name (reason != "" ? ": " reason : "") }
        }
        reason = ""
        stuckmsg = ""
    }
    /^INSTRUMENTATION_RESULT: shortMsg=/ { problem = substr($0, index($0, "=") + 1) }
    /^INSTRUMENTATION_FAILED:/ { problem = $0 }
    /^INSTRUMENTATION_CODE: -1/ { finished = 1 }
    END {
        # A test that started and never reported back was cut off by a crash
        if (running != "") {
            failed++
            print "FAIL " running ": the run stopped during this test" (running in stuck ? " (" stuck[running] ")" : "")
        }
        if (problem == "" && !finished) problem = "the run did not finish"
        if (problem == "" && passed + failed + skipped == 0) problem = "no tests ran"
        if (problem != "") print "RUN PROBLEM: " problem
        printf "%d passed, %d failed, %d skipped%s\n", passed, failed, skipped, problem != "" ? ", run problem" : ""
        exit problem != "" ? 2 : failed ? 1 : 0
    }
' "$1"
