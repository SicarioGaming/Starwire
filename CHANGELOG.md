# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/). Starwire adopted
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) in 0.2.1, so every release after it is
numbered by SemVer's rules; 0.2.1 keeps the number it was announced with.
[RELEASING.md](docs/dev/RELEASING.md#version-numbers) explains what that means for an app, and the
few places Starwire can't follow it exactly.

The release pages on [GitHub](https://github.com/SicarioGaming/Starwire/releases) have the install
steps and the checksums for each version.

## [Unreleased]

### Added

- **A Brightness tile in the stream menu,** from Moonlight X. It opens the slider for a gamepad
  or remote, and it isn't offered on Android TV, where the app can't set the screen's brightness.
- **A "Left edge swipe action" setting,** also from Moonlight X: a swipe in from the left edge
  opens the overlay menu or the brightness slider, or does nothing (brightness by default).

### Changed

- **The left edge asks for a swipe, not a tap.** A swipe in from the edge does the action above;
  a tap on the edge no longer takes touches away from the game.
- **The brightness slider hides a couple of seconds after it was last changed,** however it was
  opened; a touch on the stream closes it sooner.

## [0.2.1] - 2026-10-23

The repo changes that followed u/rambo3349's code review went live on GitHub before this release.

### Added

- **Custom resolutions and frame rates,** in the main settings and per game. These come from
  Moonlight X, credited to them.
- **A jitter buffer per game,** also from Moonlight X. It's used with host frame timing pacing.
- **The floating menu button stays where you put it:** Starwire remembers its spot for each
  orientation.
- **An about page** with credits and the full open-source licences.
- **Prebuilt libraries:** every one is traced back to its source and rebuilt byte for byte by
  GitHub ([PROVENANCE.md](docs/PROVENANCE.md)).
- **Mic measurements:** the 0.2.0 numbers are published with the raw data and a script that
  recomputes them ([here](docs/measurements/2026-10-22-mic/)), with two rounded numbers corrected.
- **Security and contributing:** a [SECURITY.md](SECURITY.md) with private vulnerability
  reporting, and a [CONTRIBUTING.md](CONTRIBUTING.md).
- **Checksums** are on the release pages, so you can check your download.
- **Tests:** 314 unit tests (from none), 32 of them screenshot tests, and 138 on-device tests. The
  same tests also run on 5 virtual devices, including a Fold shape folded, half-open and unfolded
  (thank you to u/MickeyBronson). Checks run automatically on every change.
- **This changelog,** and rules for commit messages
  ([Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/)) and branch names
  ([Conventional Branch](https://conventionalbranch.org)), checked on every pull request.
  CONTRIBUTING.md explains them. Thank you to u/stiky21 for suggesting these.

### Changed

- **Less mic delay:** the PC side plays through a faster Windows audio path. In my tests the
  median delay dropped from about 114 to 98 ms, with far fewer dropouts.
- **The mic setup window** fits small screens, like a 1280x720 stream.
- **The custom driver option** is marked experimental, with a plain warning before you turn it on.
  Starwire now checks each driver file before it loads it, so a driver imported in 0.2.0 has to
  be chosen again.
- **Clearer about what's experimental:** the custom Turnip driver is now described as an
  experimental option, not listed with the speed features.
- **Send my microphone to** has a third choice, **Not encrypted**, for Voicemeeter and other VBAN
  apps. If you used 0.2.0's default, Starwire switches you to the encrypted receiver once.
- The app says Starwire, not Moonlight, where it talks about itself, like the network test and the
  decoder crash message. In Czech, Hungarian, Turkish and Tamil those few lines show in English
  for now.
- **Real commit history:** every commit now has its own description, and the repo shares history
  with Moonlight X again, so upstream updates can be merged properly.
- Various other small cleanups to the GitHub repo and changes to wording for transparency.
- **Version numbers** follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) from now
  on, also at u/stiky21's suggestion: fixes raise the last number, new features or anything that
  needs care when you update raise the middle one. Earlier releases keep their numbers and links.
  [RELEASING.md](docs/dev/RELEASING.md#version-numbers) has the details, including why this
  release is still 0.2.1.

### Removed

- The Unlock FPS setting. The Custom frame rate takes its place.
- The 360p and 480p presets (you can still enter them as custom resolutions), and the native
  resolution and frame rate warnings.

### Fixed

- The PC's cursor comes back if Starwire is closed or crashes while it's hidden, and there's a
  "Fix PC cursor" button in the stream menu.
- No more "PC not paired" flicker on the games screen.
- No more crash on RedMagic devices when the games screen resizes.
- No more crashes from some unusual host replies, or on the resume button on Android 5 and 6.
- A rare video mix-up with async decoding after a hiccup is fixed.
- On Android 5 to 7, the background animations now stop when animations are turned off (saves
  battery).
- The in-stream menu and the games screen fit small screens better.
- Each section button at the top of Settings scrolls all the way to its heading. On-screen
  controls, and now and then About, used to stop short.
- TalkBack can read the game covers and PC cards, and the Play, Resume and Quit buttons say which
  game they're for.

### Security

- **Encrypted microphone:** the mic is now encrypted, and only devices you've paired can send
  audio to your PC. Being on the same network is no longer enough, and encryption no longer
  requires Tailscale or a similar VPN.
- **Updating from 0.2.0** needs both sides: update the app, run the new setup on the PC, then pair
  once. Until both are updated the mic doesn't work, because the new receiver refuses 0.2.0's
  unencrypted audio and a 0.2.0 receiver doesn't understand the new app.

Known issues: right-to-left languages (Hebrew, Persian) show translated text, but the layout
doesn't mirror yet. On very narrow screens, the Quit and Disconnect buttons can cut their labels
short. The custom Turnip driver option still hasn't been tried with a real Turnip driver.

## [0.2.0] - 2026-10-23

Your microphone on the PC, with any host. Talk in Discord or in-game voice chat through your
Android device while you stream, with Vibepollo, Sunshine or Apollo as they are.

### Added

- **Microphone:** Starwire sends your voice to a small receiver on the PC, which plays it into
  VB-Cable, a free virtual microphone. Discord or the game picks it up as a normal mic, and your
  host doesn't change. Turn it on in **Settings → Sound & vibration → Send my microphone**.
- Starwire turns on the device's echo cancelling and noise reduction where it has them.
- **Starwire-Microphone-for-Windows.zip** with `setup-microphone.cmd`, a one-time setup for
  Windows 10 or 11. It builds the receiver from its source with the C# compiler that comes with
  Windows, installs VB-Cable if needed (after checking VB-Audio signed it), lets the receiver
  through Windows Firewall for local-network and private VPN addresses only, starts it at every
  sign-in and plays a test tone. `-Uninstall` removes it again.
- The receiver's icon by the clock: right-click it to check the virtual mic, play a test tone
  (for Discord's mic test) or quit.
- With [logabell's Apollo build](https://github.com/logabell/apollo-microphone/releases), set
  **Send my microphone to** to **The PC's streaming app** and the microphone goes over the stream
  itself. Thanks to logabell for that code.
- A Quit button beside Resume while a game is running, so you don't have to long-press the tile.
- Games without cover art each get their own take on the Starwire tile (colour, ring, moon, glow)
  instead of all looking the same.
- Settings that change latency, smoothness, picture quality or battery have a short **Impact:**
  line under them, so you can see what turning one on or up gains or costs.
- Stream menu items that switch something on or off (Stats, Mouse mode, Keyboard, Pan and zoom,
  Touch sensitivity, On-screen controller, Menu button) show a small switch, and tapping them
  keeps the menu open.
- The stream menu header shows the game's cover art. Desktop and games without cover art get a
  Starwire tile.

### Changed

- The top of the game screen scrolls away with your games. On a small screen held sideways it used
  to fill most of the screen. Once it's gone, a slim bar keeps the selected game and its Play
  button at the top; tap the bar to scroll back up.
- The game screen fits small screens: a smaller cover and title, and the Play and settings buttons
  stack.
- On a small screen held sideways, the stream menu puts Close, Quit and Disconnect on one row, so
  more tools fit.
- On a foldable's inner screen, the stream follows how you hold it instead of forcing landscape,
  which could leave it small with black borders.
- The floating menu button is smaller, see-through until you touch it, and closes the menu as well
  as opening it.
- Softer corners, covers that arrive one after another and give a little when you press them, and
  a gently floating cover. The screen's animations pause while you stream.
- The Lite stats line uses plain words: data rate, ping, decode time, dropped frames and FPS.

### Fixed

- Settings now show on narrow screens. On a small device held upright or a Galaxy Z Fold's cover
  screen they were blank.
- Tapping a running Desktop tile to reconnect no longer crashes the app (the Resume button already
  worked), and neither does long-pressing Desktop or another icon tile. Create shortcut now works
  for them too.

Known issues: the microphone audio isn't encrypted or authenticated. Anyone else on the same
network could listen to it, or send their own sound into the PC's virtual mic, so use it only on a
network you trust, or over Tailscale. 0.2.1 encrypts it.

## [0.1.2] - 2026-10-22

### Fixed

- The crash when opening a PC's games. In 0.1.0, Starwire closed a moment after the list of games
  appeared, right as the first cover art arrived. Cover art now loads without crashing, and the
  screen picks up each cover's colour as intended.

## [0.1.0] - 2026-10-22

The first release of Starwire. If you installed it under its earlier name, Voidlink, this is the
same app renamed; it installs separately, so you can uninstall Voidlink afterwards.

### Added

- The best features of the Android Moonlight clients in one app: Moonlight X's video pipeline, the
  touchscreen tools from Artemide and Artemis, and favourites from Moonlight V+, Moonlit Artemis
  and Moonlight X Turnip. It installs next to any Moonlight you already have.
- **A new look:** the screens take their colour from the game you pick, over a starfield. Apps
  without cover art, like Desktop and Steam Big Picture, get their own icons.
- **Touchscreen tools:** the floating menu button, the Shortcuts page, pan/zoom, touch modes, the
  full keyboard, clipboard sharing, PC commands and Virtual Display.
- **Video options:** Warp Drive, Fast V-Sync, LFR and async decoding. I haven't measured how much
  they change latency in Starwire. There's also an experimental setting to load your own Turnip
  driver for PyroWave. It's off by default and hasn't been tried with a real Turnip driver yet.
- **Extras:** monitor switching, pinch to zoom anytime, changing the bitrate mid-stream, audio
  haptics and shortcuts imported from a file.

[Unreleased]: https://github.com/SicarioGaming/Starwire/compare/v0.2.1...HEAD
[0.2.1]: https://github.com/SicarioGaming/Starwire/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/SicarioGaming/Starwire/compare/v0.1.2...v0.2.0
[0.1.2]: https://github.com/SicarioGaming/Starwire/compare/v0.1.0...v0.1.2
[0.1.0]: https://github.com/SicarioGaming/Starwire/releases/tag/v0.1.0
