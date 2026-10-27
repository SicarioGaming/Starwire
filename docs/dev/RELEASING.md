# Releasing Starwire

**Status:** the one-time history switch below was done on 23 Oct 2026, so the public repo now
carries the real history, shared with Moonlight X. The release steps are still a proposal until
the next release uses them.

## Why change

Up to 0.2.0 the public repo got its commits from `private/publish-history.sh`: one commit per
top-level folder for each release, with the same 13 fixed messages every time. That made the
history impossible to follow, and it shares no commits with Moonlight X, so `git merge` from
upstream says "refusing to merge unrelated histories". The real history was never lost: the
private repo has every Moonlight and Moonlight X commit with its original author, then every
Starwire commit with a proper message.

The new flow publishes that real history and keeps it: both repos have the same `main`, releases
are plain tags on it, and a check runs before anything goes public.

## Once: switch the public repo to the real history

`.github/tools/join-history.sh` builds one merge commit with private `main`'s files, private
`main` as its first parent and public `main` as its second. (When it was run, private `main` also
had the new docs, tools and measurements, so the switch published those too.) Public `main` then moves forward to it with an ordinary push:

- no force push, so clones and the two forks keep working (`git pull`, or **Sync fork**)
- the release tags v0.1.0, v0.1.2 and v0.2.0 and their release pages stay exactly as they are
- the old per-folder commits are still there, as the merge's second parent
- `git merge moonlightx/mlsoft-x` works again

The script never pushes. It writes the commit to a local branch, `history-join`, checks it, runs
the pre-publish check and prints the push commands to run by hand: first to a `history-preview`
branch on the public repo so I can look at it on GitHub, then to both `main`s.

```
bash .github/tools/join-history.sh
```

Dry run in a scratch clone (23 Oct): public `main` fast-forwards to the join, its merge base with
Moonlight X is `7c8209cf` (Moonlight X's tip when Starwire forked), all three release tags are
reachable, and a trial merge of the latest Moonlight X gives 17 files to resolve instead of
"unrelated histories". A fresh clone grows from about 22 MiB to about 156 MiB (`--depth 1` avoids
that).

What was decided before running it (23 Oct):

- **The check's hits.** On the dry run the pre-publish check stopped on 6 commits of the real
  history that were never public. Those commits were rewritten first to take out an old account
  name and the PC's name, keeping every message, author and date, and the private repo was
  force-pushed once, with a backup branch of the old history kept there.
- **What else became public:** the old names ArtemideX and Voidlink, draft release notes, and the
  `Co-Authored-By: Claude` lines on about 125 commits (Starwire's and some of Moonlight X's).

## The pre-publish check

`.github/tools/check-publish.sh` looks at every commit a push would add to the public repo and stops
if it finds:

- a commit or tag by anyone other than `SicarioGaming <stayf8dop@gmail.com>`, or not dated in UTC.
  A commit brought in with `git cherry-pick -x` keeps its own author and date when the commit it
  names is on one of the forks' remotes: `moonlightx`, `artemide`, `turnip`, `nightjar` or `vplus`
  (Moonlight V+). Fetch each of them with `tagOpt --no-tags`, as in
  [The forks' remotes](#the-forks-remotes)
- a keystore or key file, `keystore.properties`, `local.properties`, `.env`, anything under
  `private/`, or a Reddit draft
- an access token, a private key, a written-out password, a local user folder, or a private network
  address outside the test files, in an added line, a file name or a commit message
- anything matching `private/privacy-patterns.txt`: my real name and personal accounts, the PC's
  name, addresses and folder layout, and the test devices' serials. That file is in `private/`,
  which `.gitignore` excludes, so the patterns are never published.
- placeholder words left in the README, docs or release notes

It reads the repo only, needs no network, gives the same answer every time and runs in a few
seconds. Its output says where a hit is (commit, file and line), never what matched, so it's safe to
paste. A hit I've decided to accept can be listed as `allow <commit> <path>` in the patterns file.

Run it by hand:

```
bash .github/tools/check-publish.sh public/main main
```

Or install it as a pre-push hook, once per clone:

```
printf '%s\n' '#!/bin/sh' 'f=.github/tools/check-publish.sh' 'if [ -f "$f" ]; then exec bash "$f" --pre-push "$@"; fi' 'case "$1 $2" in public\ *|*SicarioGaming/Starwire|*SicarioGaming/Starwire.git) echo "pre-push: no $f here, so nothing goes to the public repo"; exit 1;; esac' > .git/hooks/pre-push && chmod +x .git/hooks/pre-push
```

As a hook it only looks at pushes to the public repo (the remote named `public`, or its URL). There
it lets through only `main`, `history-preview` and new annotated `v*` tags, so a stray
`git push public --tags`, `--all` or `--mirror` stops before it can publish Moonlight X's tags or a
backup branch, and it runs the check on everything else. On a branch that doesn't have the script
yet, pushes to the public repo stop and pushes anywhere else go through.

Two things it can't see: the submodule (common-c commits are published from their own repo) and
what's already public. If a secret ever does get out, removing it from history doesn't help: change
the secret.

### The forks' remotes

The check only counts a cherry-pick as upstream when the commit it names is on a fork's remote in
the clone it runs in. A pick from a fork that isn't fetched there fails, as a commit by someone
else would. Add and fetch all five once per clone:

```
git remote add moonlightx https://github.com/MoreOrLessSoftware/moonlight-android.git
git remote add artemide https://github.com/derflacco/moonlight-android.git
git remote add turnip https://github.com/NoCrypt/moonlight-x-turnip.git
git remote add nightjar https://github.com/RamazanKara/nightjar.git
git remote add vplus https://github.com/qiin2333/moonlight-vplus.git
for remote in moonlightx artemide turnip nightjar vplus; do git config remote.$remote.tagOpt --no-tags; git fetch $remote; done
```

`--no-tags` keeps the forks' tags out, since some have names Starwire uses, like v0.3.0 (see
[Afterwards](#afterwards)). `check-tools.sh` checks this list matches the remotes
`check-publish.sh` counts by default.

## Version numbers

Starwire adopted [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html), or SemVer for
short, in 0.2.1, to make its version numbers mean something you can rely on. That was u/stiky21's
suggestion on Reddit
([the comment](https://www.reddit.com/r/MoonlightStreaming/comments/1xd12x5/starwire_020_mic_passthrough_wo_needing_to_modify/19rjmgziqs9kw/)),
which followed the same user's suggestion of the commit, branch and changelog rules in
[CONTRIBUTING.md](../../CONTRIBUTING.md#commits-branches-and-the-changelog)
([that comment](https://www.reddit.com/r/MoonlightStreaming/comments/1xd12x5/starwire_020_mic_passthrough_wo_needing_to_modify/19rjlelsda7id/)).
The numbers mostly worked this way already. Every release after 0.2.1 is numbered by the rules
below. 0.2.1 itself keeps the number it was announced with (point 2 below), and releases up to
0.2.0 keep the numbers, tags and download links they have.

A version is MAJOR.MINOR.PATCH, like 0.2.1. While MAJOR is 0:

- **PATCH** (0.2.1 to 0.2.2): only fixes, wording and small improvements. Updating needs nothing
  from you.
- **MINOR** (0.2.x to 0.3.0): any new feature, or any breaking change (see below).
- **MAJOR** stays at 0 for now. 1.0.0 comes when I'm ready to promise that the things below won't
  break without warning, and there's no date for it. From 1.0.0 on, a breaking change raises MAJOR,
  a new feature MINOR and a fix PATCH.

Starwire doesn't use SemVer's pre-release labels, like `0.3.0-beta.1`. SemVer allows them but
doesn't require them, and every Starwire release is a normal one. A test build for someone helping
out is shared as a debug build, which installs next to the normal app, not as a release. The app
and the Windows mic receiver are released together under one number.

The commit types make the choice mostly mechanical. If everything since the last release is
`fix`, `docs`, `test`, `ci`, `build`, `refactor`, `perf`, `chore` or `revert`, it's a PATCH
release. Any `feat`, anything marked deprecated, or any commit marked as breaking (`!` before the
colon, or a `BREAKING CHANGE:` footer) makes it a MINOR release, and so does a revert that takes a
feature out. `.github/tools/check-version.sh` checks the typed commits before a release (step 5
below), and the Release workflow runs it again on the tag. Commits brought in from Moonlight X and
the other forks keep their own messages, which have no type, so on a PATCH release the script
lists them as a warning: check none of them adds a feature.

Where it's announced: a PATCH release goes in the comments of the latest release's Reddit post,
and that post gets an update. A MINOR release gets a new post.

### What counts as breaking

SemVer is written for code libraries, where "breaking" means someone else's code stops working.
Starwire is an app, so these count instead:

- The app and the PC's mic receiver stop working with the other side from the release before.
  When that happens the mic format gets a new version too. It's SWMC version 1 today
  ([mic-protocol.md](mic-protocol.md)).
- Something you set up doesn't carry over an update: a setting, a pairing, a shortcut or a file
  you imported. A setting that's removed counts too.
- An Android version, a Windows version or a host that worked isn't supported any more.

### Where Starwire can't follow SemVer exactly

1. **Below 1.0, SemVer itself promises nothing.** Its rule for 0.x versions is that anything may
   change at any time. The PATCH and MINOR meanings above are a stricter rule of our own, and a
   common one. I've called 0.3.0 "the next major release" in posts; in SemVer terms it's a MINOR
   release, because MAJOR stays 0 until 1.0.0.
2. **0.2.1's own number doesn't follow our rule.** SemVer allows it, because of point 1, but by the
   rules above 0.2.1 would be 0.3.0. It adds features (custom resolutions and frame rates, the
   about page, a jitter buffer per game), and it breaks things: the mic only works again once both
   the app and the PC receiver are updated and paired, a driver file imported in 0.2.0 has to be
   chosen again, and the Unlock FPS setting and the 360p and 480p presets are gone. It stays 0.2.1
   because that number was announced on Reddit, in the README and in the pinned GitHub
   announcement before SemVer was adopted, and 0.3.0 is already announced as the next big
   release. Renumbering now would confuse more people than it would help. The "Updating from
   0.2.0" part of its release notes covers all three, and `check-version.sh` skips its bump check
   for v0.2.1 only.
3. **The public API is a list in plain words.** SemVer's first rule is that a project declares a
   public API, in its code or its docs, and that the declaration is precise. For an app, the list
   under "What counts as breaking" is that declaration. Because it's plain words rather than code,
   "is this breaking?" stays a judgement call that no tool can fully check.
4. **Changes outside Starwire.** An update to the host (Vibepollo, Sunshine or Apollo), to Android
   or Windows, or to VB-Cable can change how Starwire behaves without its number changing. The
   number only covers Starwire's own changes.
5. **Android needs a second number.** Besides the version name, Android uses `versionCode`, a
   whole number that goes up with every release so Android knows which APK is newer. It was 318
   for 0.2.0. It isn't part of SemVer and you won't normally see it. `check-version.sh` checks
   that it went up when a release is tagged. The unit tests don't, so raise it with the version.

## Releasing after the switch

Work happens on branches in the private repo. `main` is released code and docs, the same in both
repos. The repo's own git identity is `SicarioGaming`; set `TZ=UTC` for every commit and tag so the
dates are in UTC.

1. On the release branch: pick the number by the rules in [Version numbers](#version-numbers), then
   the version and notes as in [UPDATING.md](UPDATING.md) (section 3, steps 1 and 2): raise
   `versionName` and `versionCode` in app/build.gradle together, and move CHANGELOG.md's
   `[Unreleased]` lines under the new version. Reword the README rows that say "Coming in the
   next release" or "Coming in" this version to say "From" it, like "From 0.2.1"
   (NextReleaseRowsTest fails until you do). The release checklist done
   and the on-device tests passing (`.github/tools/device-test.sh -s <serial> --allow-stream`).
2. Merge it into `main` as one merge commit:

   ```
   git switch main
   TZ=UTC git merge --no-ff <branch> -m "Starwire X.Y.Z"
   ```

3. Tag it, annotated:

   ```
   TZ=UTC git tag -a vX.Y.Z -m "Starwire X.Y.Z"
   ```

4. If the release moved the moonlight-common-c submodule, push that commit to the public common-c
   repo first, and check it's there:
   `git -C app/src/main/jni/moonlight-core/moonlight-common-c branch -r --contains <commit>` should
   list its public `main`.
5. Check it. The version check must pass, and both publish checks must print `clean`:

   ```
   bash .github/tools/check-version.sh --tag vX.Y.Z
   bash .github/tools/check-publish.sh public/main main
   bash .github/tools/check-publish.sh public/main vX.Y.Z
   ```

6. Push `main` first, wait for its tests, then push the tag, to the public repo one ref at a time.
   The Release workflow runs no tests of its own, so the tag waits until the **Build** workflow has
   passed on that commit of public `main`:

   ```
   git push origin main
   git push public main
   gh run watch --repo SicarioGaming/Starwire --exit-status "$(gh run list --repo SicarioGaming/Starwire --workflow Build --branch main --limit 1 --json databaseId --jq '.[0].databaseId')"
   git push origin vX.Y.Z
   git push public vX.Y.Z
   ```

   The tag push to the public repo runs the **Release** workflow, which builds and signs the APK and
   publishes the release with the notes and checksums. (If Actions are on in the private repo, the
   tag push there runs it too and stops at once, because the signing secrets are only on the public
   repo. That's expected.) The tag push to the public repo also runs the **Devices** workflow's
   release tier, all 17 emulator legs; it doesn't hold the release back, so check its summary
   afterwards. On the private repo it only runs by hand (Actions > Devices > Run workflow).
7. `gh release view vX.Y.Z --repo SicarioGaming/Starwire` lists the APK (and the microphone zip)
   with checksums.

Never toward the public repo: `--force`, `--tags`, `--all` or `--mirror`, moving a tag that's
already out, or rewriting a commit that's already out. Wording fixes are new commits.

## Afterwards

- Move `private/publish-history.sh` to `private/archive/`, since nothing uses it any more.
- Done on 23 Oct: Moonlight X's own `v0.x` tags (v0.2.0 to v0.6.9, 31 of them) and Turnip's
  `v12.1-turnip` had come into the private repo with the forks' history, and some had the same
  names Starwire will use, like v0.3.0. Moonlight X's are now `moonlightx/v0.3.0` and so on, and
  Turnip's is `turnip/v12.1-turnip`, in the private repo and the maintainer's clone, so the plain
  `vX.Y.Z` names are Starwire's alone. Artemide's tags (like `Experimental_7.1`) stay as they
  are, since none of them starts with `v`. Fetches from the forks no longer bring tags
  (`git config remote.moonlightx.tagOpt --no-tags`, and the same for `artemide` and `turnip`,
  and later `nightjar` and `vplus`); set that in any new clone, as in
  [The forks' remotes](#the-forks-remotes). The public repo only ever had Starwire's tags, and nothing changed
  there. In the private repo, v0.2.0 now matches the public one. Its v0.1.0 and v0.1.2 are the
  private tags made at those releases, on different commits from the public ones (which mark the
  per-folder commits published at the time), and `starwire-v0.2.0` is left from when v0.2.0 was
  taken.
- In UPDATING.md, point section 3 at this page. Its step 3 (`git push origin v<version>`) only
  reaches the private repo, and `set-release-secrets.ps1` gives the signing secrets to the public
  repo only, so that push can't make a release.
- Bring Moonlight X in with real merges (`git merge moonlightx/mlsoft-x`), which keeps its authors,
  and port single commits from other forks with `git cherry-pick -x`, which records where each one
  came from.
