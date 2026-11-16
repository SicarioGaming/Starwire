# Keeping Starwire current

Starwire follows several Moonlight versions. When they add something good, this is how it gets
into Starwire and out to people.

## 1. See what's new

Every Monday a GitHub workflow runs `.github/tools/upstream/check.sh` and opens (or updates) an issue
called **Upstream changes** listing new commits in each source. To check by hand:

```bash
.github/tools/upstream/check.sh
```

The sources and the last commit reviewed for each are in `.github/tools/upstream/sources.txt`.

## 2. Bring a feature in

- **Ported code** (Moonlight X, Artemide, Moonlight X Turnip, Moonlight V+, Nightjar,
  moonlight-common-c): add the source as a git remote, fetched without tags as RELEASING.md's
  [The forks' remotes](RELEASING.md#the-forks-remotes) says, and bring each commit in with
  `git cherry-pick -x`, which keeps its author and records where it came from. Starwire's base
  has moved on, so expect to resolve conflicts by hand.
- **Ideas** (Moonlight V+, Moonlit Artemis, upstream Moonlight): write the feature for Starwire.
- Add an on-device test for it (`app/src/androidTest`) and run them all, with the smoke tests:
  `./gradlew assembleNonRootDebug assembleNonRootDebugAndroidTest`, then
  `.github/tools/device-test.sh -s <serial>` (`--allow-stream` adds a short Desktop stream). It
  installs with `adb install -r` and never uninstalls, so the debug app keeps its pairing, which
  `connectedNonRootDebugAndroidTest` may not.
- Credit it in the README's **Where every feature comes from** table (project, author, and
  whether it was ported or an idea). A new project in the README's **Credits** table goes in
  `AboutCredits.java` too, which is the Credits page in Settings → About.
- A new or updated library, prebuilt or submodule goes in `OpenSourceNotices.java` (name,
  version, licence) with its licence text in `app/src/main/assets/licenses`. The on-device test
  `OpenSourceNoticesTest` fails if a native library has no entry.
- Move that source's last-reviewed commit forward in `.github/tools/upstream/sources.txt`, even for
  commits you decided to skip.

## 3. Release it

1. Raise `versionName` and `versionCode` in `app/build.gradle`.
2. Write the notes in `.github/release-notes/v<version>-notes.md` (plain, first person). Leave
   `APK_SHA256`, `ZIP_SHA256` and `CERT_SHA256` where the checksums go; the release fills them
   in. If the notes have no `CERT_SHA256`, the release adds a **Check your download** part above
   the closing `GPL-3.0.` line by itself.
3. Merge, tag, check and publish as [RELEASING.md](RELEASING.md#releasing-after-the-switch) says,
   in its steps 2 to 7: the tag is annotated, the version and publish checks come first, and
   `main` and the tag go to the public repo one ref at a time.

The **Release** workflow builds the signed APK, checks its signature, and publishes the GitHub
release with the notes and checksums. It stops if the APK isn't signed with Starwire's release
certificate (`B6:04:1D:…:3F:97`, written out in the README's **Check your download**). It needs these repository secrets (Settings → Secrets and
variables → Actions), set once:

| Secret | Value |
| --- | --- |
| `STARWIRE_KEYSTORE_BASE64` | the release keystore file, base64-encoded |
| `STARWIRE_KEYSTORE_PASSWORD` | the keystore password |
| `STARWIRE_KEY_ALIAS` | `starwire` |
| `STARWIRE_KEY_PASSWORD` | the key password |

With the GitHub CLI signed in, `.\.github\tools\set-release-secrets.ps1` sets all four on the
public repo from `keystore.properties`, without showing the passwords.

Tags with a hyphen (like `v0.2.0-beta1`) are published as pre-releases.
