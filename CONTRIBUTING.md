# Contributing to Starwire

Thanks for taking a look. Starwire is a one-person project, so bug reports and ideas help the
most.

## Bugs and ideas

- **Bugs:** open an issue with the
  [Bug report form](https://github.com/SicarioGaming/Starwire/issues/new/choose). It asks for
  your Starwire version, your device and Android version, and the host on your PC. Steps to make
  it happen again help a lot.
- **Ideas:** post them in [Discussions](https://github.com/SicarioGaming/Starwire/discussions),
  or use the **Feature idea** form if you already know what you'd like. Say if another Moonlight
  app already has it.
- **Security problems:** please follow [SECURITY.md](SECURITY.md) instead of opening a normal
  issue with the details.

## Building

The short version, with the NDK version from `app/build.gradle` installed:

```bash
git clone --recursive https://github.com/SicarioGaming/Starwire.git
cd Starwire
./gradlew assembleNonRootDebug
```

The debug APK lands in `app/build/outputs/apk/nonRoot/debug/` and installs as "Starwire (Debug)"
(`com.starwire.debug`), next to the release app. The README's **Building from source** part (under
[More details](README.md#more-details)) covers the requirements, signed release builds and the
prebuilt libraries.

## Tests

Most tests need no device. Only one needs a host: the stream smoke test, which runs only when
you ask for it.

- **JVM unit tests** in `app/src/test` run on the computer:

  ```bash
  ./gradlew testNonRootDebugUnitTest
  ```

  Lint runs the same way (`./gradlew lintNonRootDebug`). The warnings it already had are listed
  in `app/lint-baseline.xml`, so only a new one fails it.
- **Screenshot tests** draw the settings, game, in-stream menu and PC list screens on the
  computer (Robolectric and Roborazzi) at 8 sizes, from a Fold5 cover screen to a tablet, and
  compare them pixel for pixel with the pictures in `app/src/test/screenshots`:

  ```bash
  ./gradlew verifyRoborazziNonRootDebug   # every JVM test, and fails on any changed pixel
  ./gradlew recordRoborazziNonRootDebug   # after changing a screen on purpose
  ```

  A failed check leaves each changed screen in `app/build/outputs/roborazzi` as the old picture,
  the difference and the new one. After recording, look at every picture that changed and commit
  them with the change. The pictures were recorded on Windows; whether Linux draws exactly the
  same pixels hasn't been checked yet.
- **Native tests:** a few C++ simulations of the frame pacer under
  `app/src/main/jni/vulkan/tests`, inherited from Moonlight X, and two under
  `app/src/main/jni/moonlight-core/tests`: `partial_frame_test.sh` checks moonlight-common-c's
  partial frames, and `host_packets_test.sh` checks a few of the packets it sends a host against
  what the host understands. The **Native host tests** step in `.github/workflows/build.yml`
  shows how to build and run them.
- **On-device tests** (JUnit 4, instrumented) in `app/src/androidTest`, written for and run on my
  RedMagic Astra tablet. The smoke tests among them use the app the way a person would, with UI
  Automator. The ones that need a PC skip, and say why, when the device has no paired PC or it
  isn't online.
- **The Windows microphone receiver's tests** in `windows/tests`: `run-tests.ps1` checks the
  encryption and pairing code against the same test vectors as the app, and `e2e-loopback.ps1`
  pairs a test sender with a receiver on the PC's loopback and streams to it. Both build with the
  C# compiler that comes with Windows and leave an installed receiver alone.

To run the on-device tests on a connected device, build both APKs, then run `device-test.sh` with
the serial that `adb devices -l` shows:

```bash
./gradlew assembleNonRootDebug assembleNonRootDebugAndroidTest
.github/tools/device-test.sh -s <serial>
```

It installs with `adb install -r` and never uninstalls, so the debug app keeps its pairing with
your PC. After the instrumented suite it runs the smoke tests, saves their screenshots and looks
for crashes. A test that hangs for 90 seconds fails with what the app's main thread was doing,
and the run goes on to the next test (`StuckTestWatchdog` in `com.limelight.testing`).
`--allow-stream` adds a short Desktop stream to the paired PC; without it nothing is streamed. `./gradlew connectedNonRootDebugAndroidTest` runs the suite too, but it may not keep the
pairing.

Or by hand with `am instrument`, which is also how to run one class:

```bash
./gradlew assembleNonRootDebug assembleNonRootDebugAndroidTest
adb install -r app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk
adb install -r app/build/outputs/apk/androidTest/nonRoot/debug/app-nonRoot-debug-androidTest.apk
adb shell am instrument -w com.starwire.debug.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class com.limelight.binding.video.CustomVulkanDriverTest com.starwire.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The layout tests (`ScreenWidthTest`, `SettingsWidthTest`) check whatever screen size the device
has. To try a narrow screen on a tablet, run `adb shell wm size <width>x<height>` first and
`adb shell wm size reset` afterwards.

CI (`.github/workflows/build.yml`) runs the JVM tests, lint, the native tests and
`.github/tools/check-tools.sh` (which checks the scripts in `.github/tools` still work) on every
push, and builds the app and the test APK.

`.github/workflows/devices.yml` runs the on-device tests on emulators shaped like phones,
foldables, tablets, handhelds and Android TV, from Android 5 up: 5 of them on pull requests, 13
every Sunday and all 17 for a release tag. The list is `.github/devices/matrix.json`. Each one
runs `device-test.sh`. Then the foldables fold and unfold with the app open, and other legs
change the text size, display size, theme, language or orientation, with the layout and smoke
tests run again after each change. Some end with random input. Emulators have no hardware
decoder, Adreno GPU, real controller or microphone, so tests that need one skip there
(`DeviceCaps.assumeHardware`). To run one leg on an emulator you started yourself:
`.github/tools/device-run.sh --leg <id> -s emulator-5554`. On GitHub the matrix runs by itself on
the public repo, and on the private repo only when started by hand.

What the tests don't cover yet, to be honest about it: the custom Turnip driver option with a
real Turnip driver, hosts other than Vibepollo, and the encrypted microphone from a real device to
a real PC, which so far has only been checked by hand (from my tablet over Wi-Fi).

## Pull requests

If you'd like to change something, open an issue or a discussion first so we can agree on it.
Keep each pull request to one change, and say what you tested it on.

## Commits, branches and the changelog

Commit messages follow [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/),
branch names follow [Conventional Branch](https://conventionalbranch.org), and
[CHANGELOG.md](CHANGELOG.md) follows [Keep a Changelog 1.0.0](https://keepachangelog.com/en/1.0.0/).
Commits before 23 Oct 2026 predate these rules. The history is public, so they stay as they are.

**Commit messages** start with a type, an optional scope in brackets, a colon and a space:

```text
fix(mic): stop the receiver dropping audio after a reconnect
feat(dualsense): add adaptive trigger effects
docs: add the 0.2.1 notes to CHANGELOG.md
```

- The types used here: `feat` (a new feature), `fix` (a bug fix), `docs`, `test`, `ci`, `build`,
  `refactor`, `perf`, `chore` and `revert`.
- The scope is optional and in lower case, like `fix(mic): ...` or `feat(dualsense): ...`.
- After the colon, write the subject in the imperative and in lower case: "fix: stop the crash",
  not "fix: Stopped the crash".
- A change that breaks something people rely on gets a `!` before the colon
  (`feat(mic)!: ...`) or a `BREAKING CHANGE: <what breaks>` footer.
- If there's a body, leave a blank line after the subject.

**Branch names** are `main`, or a type and a slash followed by lower-case words with hyphens:
`feature/`, `bugfix/`, `hotfix/`, `release/` or `chore/`. For example `feature/dualsense-triggers`,
`bugfix/mic-echo` or `release/v0.2.1` (dots are fine in version numbers). The other names the spec
allows, like `feat/` and `fix/`, pass the check too.

**The changelog:** every user-facing change adds a line under `[Unreleased]` in CHANGELOG.md, in
the same pull request, under Added, Changed, Deprecated, Removed, Fixed or Security. A release
moves the `[Unreleased]` lines under a new version heading with its date, like
`## [0.2.1] - YYYY-MM-DD`, and updates the compare links at the bottom.

**Version numbers** follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html), as
u/stiky21 suggested on Reddit along with the rules above. Every release after 0.2.1 is numbered
this way (0.2.1 keeps the number it was announced with): a release with only fixes raises the last
number, and any `feat` or breaking commit raises the middle one while Starwire is below 1.0. There
are no beta versions like `0.3.0-beta.1`: test builds are shared as debug builds, not releases.
[RELEASING.md](docs/dev/RELEASING.md#version-numbers) says what counts as breaking for an app.

**The check:** `.github/workflows/commits.yml` runs `.github/tools/check-commits.sh` on the
commits and branch name of every pull request, and on every push to `main`. It skips merge
commits, commits cherry-picked from upstream with `git cherry-pick -x`, and commits from before the
rules started. To run it yourself before pushing:

```bash
bash .github/tools/check-commits.sh                      # what your next push adds
bash .github/tools/check-commits.sh origin/main..HEAD    # what a pull request into main adds
bash .github/tools/check-commits.sh --branch "$(git branch --show-current)"
```

To have each message checked as you commit, add it as a commit-msg hook:

```bash
printf '#!/bin/sh\nexec bash .github/tools/check-commits.sh --msg-file "$1"\n' > .git/hooks/commit-msg
chmod +x .git/hooks/commit-msg
```

The hook lets git's own "Merge ...", "Revert ..." and `fixup!` messages through, so those commands
keep working. Before opening a pull request, squash the fixups and reword a revert as
`revert: <what it undoes>`. It also lets an upstream commit through when `git cherry-pick -x`
brings it in, as the check above skips those.

To fix a message that's already committed, use `git commit --amend` for the latest commit, or
`git rebase -i origin/main` and "reword" for older ones, then push with
`git push --force-with-lease`.

## Licence and credits

Starwire is GPL-3.0 ([LICENSE.txt](LICENSE.txt)), like Moonlight and every fork it builds on, and
anything you contribute is under the same licence. Starwire keeps its upstream credits: code
ported from another Moonlight version stays credited to its author in the README's
[Where every feature comes from](README.md#where-every-feature-comes-from) tables and the
[Credits](README.md#credits) table, and existing copyright and licence notices stay as they are.
If you port something, add its source and author there too.
