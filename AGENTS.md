# Working on Starwire

Rules for anyone changing Starwire, people or coding agents. Starwire is an Android Moonlight
client (Java, with native code under `app/src/main/jni`) built on Moonlight X, plus a Windows
microphone receiver in `windows/`. Keep this file short: under 100 lines.

## Before you start

Write three or four plain lines saying what "done" looks like: what the user will see, which test
proves it, and which docs change. If you can't write them, the task isn't clear yet.

## Build and test

- Debug APK and test APK: `./gradlew assembleNonRootDebug assembleNonRootDebugAndroidTest`
- Fast JVM unit tests, no device needed: `./gradlew testNonRootDebugUnitTest`
- Lint: `./gradlew lintNonRootDebug`. Old warnings are listed in `app/lint-baseline.xml`; a new
  one fails.
- On a device: `.github/tools/device-test.sh -s <serial>` installs both APKs with `adb install -r`,
  runs the instrumented suite and the smoke tests, and saves screenshots. Never uninstall the
  debug app on a device that's paired with a PC: pairing again needs the PC's PIN.
- Prebuilt libraries: `.github/tools/prebuilts/verify.sh` rebuilds them from pinned upstream
  source and compares the bytes (see `docs/PROVENANCE.md`).
- The Windows receiver is built on the user's PC by `windows/setup-microphone.ps1`. To test a
  change, build it into a temporary folder and use a non-default port; don't touch an installed
  copy.

Check a script exists before relying on it; some of these are new.

## Rules

- Fix what's broken before adding features. Every change comes with a test, and every bug fix
  with a regression test that fails without the fix.
- Prefer scripts and tests to checking things by hand. Tests must be deterministic: no wall
  clock, no network, no randomness without a fixed seed.
- Claim only what was tested or measured, in the README, release notes and the app's own text.
  Say plainly what hasn't been tried.
- Treat every network as hostile. A private address is a filter, not a security boundary.
- Security-sensitive changes get their own review pass and the maintainer's sign-off: the
  microphone's encryption and pairing, loading driver files, pairing and certificates with the
  host, and release signing.
- Borrowed code keeps its author. Bring upstream changes in with `git cherry-pick -x`, and
  credit them in `CoverGlowSettings.CREDITS` and the README's "Where every feature comes from".
- Match the surrounding code: naming, comments and idiom. User-facing text is plain English with
  short sentences, no em dashes and no hype. New strings go in
  `app/src/main/res/values/strings.xml`.

## Git

- Small commits, dated in UTC, with messages that follow
  [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/):
  `type(scope): description`, in lower case and the imperative, like
  `fix(mic): stop the echo after a reconnect`. Types: `feat`, `fix`, `docs`, `test`, `ci`,
  `build`, `refactor`, `perf`, `chore` and `revert`. The scope is optional and names the area,
  like `mic`, `settings` or `game-screen`. A breaking change gets a `!` before the colon.
- Branch names follow [Conventional Branch](https://conventionalbranch.org): `feature/`,
  `bugfix/`, `hotfix/`, `release/` or `chore/`, then lower-case words with hyphens, like
  `bugfix/mic-echo` or `release/v0.2.1`. `main` has no prefix.
- Every user-facing change adds a line under `[Unreleased]` in `CHANGELOG.md`
  ([Keep a Changelog 1.0.0](https://keepachangelog.com/en/1.0.0/)), along with the change.
- `.github/tools/check-commits.sh` checks messages and branch names, in CI and as a commit-msg
  hook; CONTRIBUTING.md says how. Commits from before the rules started (23 Oct 2026) stay as
  they are.
- Never commit `keystore.properties`, signing keys, or anything under `private/`.
- Nothing reaches the public repo unless `.github/tools/check-publish.sh` passes.
- One task per session. Start a new session for unrelated work instead of stretching one.
