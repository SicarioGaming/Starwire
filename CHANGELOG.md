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

## [0.3.0] - 2026-10-28

### Added

- **A Brightness item in the stream menu,** which opens the brightness slider. A controller moves
  it in 5% steps, and A, B or Back closes it. It isn't shown on Android TV. From Moonlight X.
  Checked by tests, not yet tried in a stream.
- **A Left edge swipe action setting,** in Settings under In-stream menu. It says what a swipe in
  from the left edge of the screen does while streaming: open the brightness slider, open the
  stream menu, or nothing. It's off (Disabled) by default, so the swipe goes to the game until you
  pick something else. Moonlight X, where it comes from, opens the slider by default. Checked by
  tests, not yet tried in a stream.
- **Shortcuts for Windows' virtual desktops in the stream menu:** Win+Ctrl+Left and
  Win+Ctrl+Right (previous and next desktop) side by side, then Win+Tab (Task View), on the
  Shortcuts page. Checked by tests, not yet tried in a stream.
- **Back opens the menu,** on by default. Back (the navigation bar's button or the back gesture)
  now opens the stream menu instead of ending the stream, and Back again closes it. With the
  brightness slider up from the left edge swipe, Back closes the slider first. To leave, use
  Disconnect or Quit Session in the menu. A short press of a TV remote's Back opens the menu too,
  where it used to end the stream; holding it still opens the menu as before. A switch in
  **Settings → In-stream menu** turns it off, and Back ends the stream again. A mouse right-click
  that Android sends as Back still reaches the PC as a right-click, and a controller's B still
  goes to the game. The idea comes from Artemis (Yukino Song), via Artemide. Thank you to
  u/Glove5751 for asking for it. Tested in JVM tests only, with a stand-in for the remote. Not yet
  tried on a phone, a tablet or a TV.
- **Controller vibration strength,** under Controllers: 0 to 100% of the game's rumble, on every
  controller and on this device when it vibrates in place of one. Audio haptics on the controller
  follows it too. 0% turns both off, and new installs start at 100%, as before. Checked by unit
  tests only so far: pads on Starwire's USB driver, pads whose motors Android drives, built-in
  controls, the on-screen controls and the fallback to this device. No test covers Shield
  controllers, or audio haptics, which goes through the same code as the game's rumble. It hasn't
  been tried on a device or felt on a real controller yet, the G8+ included.
- **DualSense trigger effects over Bluetooth (Experimental, off by default),** in Settings,
  Controllers, DualSense. On Android 12 and newer, a DualSense paired over Bluetooth gets the
  game's own adaptive trigger effects from a host that sends them (going by its source,
  Vibepollo does when it emulates a DualSense), or one of four presets when the game sends
  none: light resistance, gun click, heavy pedal or buzz. The game's effects win over a preset,
  and "Off" turns both off. Trigger strength has 8 steps and uses the pad's own power limit.
  Starwire checks the link every time the pad connects and goes back to the usual path if
  anything fails; the status line says why. Test sends one effect. Rumble and the light bar work
  as before. It asks for the Nearby devices permission and uses a hidden Android call, through
  HiddenApiBypass (LSPosed), whose files Gradle checks by SHA-256. How the reports reach the pad
  and how the pad is found are ported from Moonlight V+ (qiin2333), with two changes: setReport is
  only used on a phone without sendData, and a DualShock 4 is never taken for the pad. The code
  that passes the host's effects to Java is Nightjar's (Ramazan Kara). Only the trigger modes
  Nielk1's notes list reach the pad; any other mode turns the trigger off. **Not tried on a pad
  yet:** Starwire's trigger effects haven't been tried on a real DualSense, and nobody has felt
  the presets or the strength steps. A separate test app used the same hidden sendData call on
  one tablet and one DualSense. It changed the light bar, and four trigger effects felt as
  expected: firm resistance, a weapon click, a buzz and off. setReport did nothing there.
  Starwire's presets use other values, and light resistance and heavy pedal use a kind of effect
  that test didn't try. Tested with JVM tests against a fake Bluetooth link.
- **A log line for each DualSense trigger effect a game sends,** while Trigger effects is on, so
  a screen recording can be matched to the effects the game really sent. Each line starts with
  `DualSense effect:` and gives the time since the stream screen opened, the trigger (R2 or L2),
  the effect's mode and bytes in hex as the PC sent them, and what Starwire did with it: passed
  it on to the pad, held it because no pad was ready yet, turned the trigger off for a mode it
  doesn't pass on, or ignored it for the Off preset. A trigger's effect is logged when it
  changes, not each time the game sends it again. After 10 lines in a second, counted from the
  first of them, changes are only counted until that second is up. Then a line says how many
  were left out, if any, and each trigger's last change is logged with its own time. Nothing
  about the pad, the PC or you is in it. Checked by JVM tests, not yet read on a device.
- **USB devices (Experimental):** share a wired device plugged into the phone with the PC you
  stream from. Turn on "USB devices (Experimental)" under Controllers (off by default), and the
  stream menu gets a "USB devices" page. It lists what's plugged in: which devices can't be shared
  and why, which ask you first (keyboards and mice), and that the rest are checked in full when you
  share them. Choosing one asks Android's permission and shares it; choosing it again, unplugging
  it or ending the stream gives it back, and after a lost connection, or the phone's network going
  away, it offers to share it again. The device goes through the USB/IP exporter from Moonlight V+
  (qiin2333), built on usbipdcpp (yunsmall), and an encrypted tunnel to the PC's USB helper, tied
  to the microphone's pairing ([usb-protocol.md](docs/dev/usb-protocol.md)). Starwire's device
  rules decide before anything is shared: pads, wheels, sticks and audio devices pass, keyboards
  and mice need your yes each time, and hubs, storage, network adapters, vendor-specific devices
  (wired Xbox pads among them, for now) and USB 3 devices are refused, with the reason. A device
  that turns out to be a keyboard or mouse only once Starwire reads all of it isn't shared, and
  asks you then. One device at a time, on Android 9 or later on 64-bit ARM phones and tablets:
  x86_64 Chromebooks and emulators don't get the setting, even ones that run ARM apps. Tested
  only on a computer: the stream menu's page, the exporter with its device rules and the tunnel
  together, against a fake PC helper and a stand-in for the exporter's native part on 127.0.0.1.
  Not tried yet: sharing a USB device on a phone or tablet, the real helper, Windows, and the
  question for a keyboard with a controller. The APK carries the exporter for 64-bit ARM (and
  x86_64 in debug builds), and its licences are in Settings > About > Open-source licences. The
  setting's credit line names Moonlight V+ and usbipdcpp, as Settings > About > Credits does.
- **The PC's side of sharing a USB device from the phone.** Starwire's USB helper
  for Windows (`setup-usb.cmd`, in the Windows zip) can now take a device over the encrypted USB tunnel
  ([usb-protocol.md](docs/dev/usb-protocol.md)): from a phone paired with the PC's microphone
  receiver, once USB sharing is on in its menu and Allow USB is ticked there for that phone. It
  checks the device against the device rules, attaches it with usbip-win2's driver through a
  relay on the PC itself, checks what the device tells Windows, and detaches only its own device
  when the session ends. USB sharing is off until you turn it on, and the firewall rule covers
  Private and Domain networks only. The app's side (USB devices, above) and this helper haven't
  been tried together yet. Tested on Windows 11 with a fake driver and a test phone over 127.0.0.1, and
  with the shared test vectors and device rules' cases. It hasn't talked to a real phone or
  attached a real device, and setup-usb.ps1 has only been tried without installing anything.
  The helper's tunnel server was checked against a hostile local network, and these hold in its
  tests: when a session is stuck because the relay is blocked writing to a phone that stopped
  reading, or blocked writing to a driver that stopped reading, unpairing, Stop sharing and
  turning USB off still close the sockets, end the session and detach the device; the number of
  waiting connections stays within its cap and the per-address record is pruned under a flood, so
  the per-connection worker threads stay bounded; each address is rate limited to one new waiting
  connection every two seconds, so one host can't churn the waiting room or starve another's BIND;
  a BIND whose connection is closed stops waiting and reads no pairings; a flood of BINDs leaves a
  pairing-file read a second for the sharing phone's pairing check and the menu; the running
  pairing check ends the session if the pairings file can't be read twice in a row; and the menu
  reads the paired phones off the icon's thread. The tests run over 127.0.0.1 with a fake driver
  and a test phone; the send timeouts are turned off in the teardown tests, so they prove the
  teardown itself finishes. Not tried with a real phone, a real device or the real driver.

### Changed

- **The Starwire wordmark** on the games screen is a little bigger. Beside a long PC name it
  shrinks to make room, back to the size it was before, or further if the name would still reach
  it. It stays the same size on screens under 360 dp wide, like a Fold's cover screen. Checked
  by tests and screenshots on a computer, not yet seen on a device.
- **Virtual display** is the new name of the setting that was called "Display on the PC", in
  Settings under PC and in a game's App Settings, so it's easier to find. Its choices are now Host
  default, On (a screen made for this device) and Off (the PC's main monitor). A choice you
  already made stays as it was. Thank you to u/elenayebenes for asking about it. Checked by
  tests, not yet tried on a device.
- **Quit Session in the stream menu asks first,** because it closes the game on the PC and
  anything unsaved there is lost. Yes quits. No or Back keeps streaming. Tests answer it by touch,
  and with a controller's A and B buttons as Android passes them on. From Moonlight X. Not yet
  tried in a stream, so we don't know yet which answer a controller starts on. Two catches with a
  controller. With Flip face buttons on, the question doesn't swap A and B the way the menu does,
  so the button that picked Quit Session cancels it; the bitrate and touch mode pickers already
  work that way. And a controller run by Starwire's own USB driver can't answer it, as it can't
  move through the menu either.
- **Google's video decoders, as on Pixel phones, are asked for the most frames they can handle**
  at the stream's size, instead of just the stream's frame rate. They set their speed to just
  meet what they're asked for. Moonlight X measured about 9 ms a frame before and about 7.8 ms
  after, at 120 FPS on a Pixel 10 Pro. From Moonlight X. Checked by tests, but not tried here:
  we don't have a Pixel.
- **The stats box has rounded corners.** From Moonlight X. Checked by a test, not yet seen in a
  stream.
- **The Windows zip has the USB helper's files too,** next to the microphone's, which are the
  same as 0.2.1's. One script makes the zip, for the release and on every push, and stops if a
  file either setup needs is missing.

### Fixed

- The games screen takes a new font size from Android's settings straight away. The PC name,
  the wordmark and the selected game used to keep the old size until you opened the screen again.
  Checked by tests, not yet tried on a device.
- Prevent packet loss sent a packet type that Sunshine, Apollo and Vibepollo don't know. They log
  each one at their debug level, so Vibepollo's log got about 50 lines a second. It now sends a
  ping, which these hosts log only at a more detailed level. A native test checks the new packet.
  A stream with Vibepollo logging at debug level hasn't been tried with this build yet.
- Starwire no longer sends Sunshine, Apollo and Vibepollo a message GeForce Experience needs to
  turn on controller vibration. Going by their source code, these hosts send vibration without
  it. Vibepollo warned about the message each time a stream started. GeForce Experience still gets
  it. A native test checks which hosts get it, but controller vibration hasn't been tried with
  this build yet.
- **A tap near the left edge of the screen goes to the game** like any other tap. It used to open
  the brightness slider instead. Now the stream menu's Brightness item opens the slider. A swipe
  in from the left edge can open it too, but that's off by default. To turn it on, go to
  Settings, In-stream menu, Left edge swipe action, and pick Brightness adjustment. The swipe
  works in the trackpad, direct touch and multi-touch modes. It starts within 80 dp of the left
  edge (about 1.3 cm) and moves 60 dp to the right (about 1 cm) within half a second. That isn't
  fast, so a slow drag to the right from near the edge, like moving the cursor, opens the slider
  too. That's why it's off by default. The drag goes to the game until it has moved 60 dp, and
  the rest of it doesn't. The slider also works differently once it's open. It used to hide 3
  seconds after it opened. Now it stays up until you change it, then hides 2 seconds after your
  last change. Until then, a touch on the stream closes it, and none of that touch reaches the
  game. From Moonlight X. Thank you to u/zolidz64 for reporting it. Checked by tests, not yet
  tried in a stream.
- **Multi-touch mode sent the PC mouse clicks instead of touches.** It worked just like direct
  touch, and had since 0.1.0. Now each finger goes to the PC as a touch of its own, two at a
  time. If the PC can't take touches, the mode still works like direct touch. The three-finger
  tap for the stream menu works in this mode too, and so does the left edge swipe once it's
  turned on. Off, as it is by default, the swipe reaches the PC as a touch. As in the other
  modes, a third finger lets go of what the first two were doing on the PC, so touches with three
  or more fingers don't reach it. Checked by tests, not yet tried in a stream.
- Starwire no longer tries to show an error or a "please wait" box on a screen Android has
  already closed. That could happen when a connection error or the host's answer came in late.
  This is tested on a computer, not yet on a phone. The idea is from Moonlight V+ (qiin2333).
- If you change the bitrate mid-stream again while the last change is still on its way, your
  latest choice now wins. Before, the older change could reach the host last, or its answer
  could come back last, and the stream or the menu ended up on the older choice. The changes now
  go to the host one at a time, and only the latest is shown. This is tested with a stand-in
  host, not yet with a real PC. The idea is from Moonlight V+ (qiin2333).
- **The last setting in Settings scrolls clear of the gesture bar.** With gesture navigation,
  the see-through bar at the bottom of the screen covered it. The list still runs behind the bar,
  and its last row now scrolls up above it. The same code also leaves room for the bar under the
  game list, the PC list, per-game settings, the Quick Launch app picker and the Credits and
  Open-source licences pages; no test checks those screens yet. From Moonlight X. Checked by
  tests, not yet tried on a device.
- A controller button or keyboard key you were holding when you opened the stream menu stayed
  pressed on the PC until you pressed it again. Every way of opening the menu now lets go of both
  first: the floating button, Back (which opens the menu now, see Added), a TV remote's Back,
  holding Select on a controller, the three-finger tap and the left edge swipe. Holding Select
  always let go of controller buttons, but not of keys. Tested in JVM tests only. Not yet tried
  on a device.
- **The left part of the stream menu was cut off** when the floating menu button was off. The
  game showed through more than a third of the menu, until something made Android lay out the
  screen again, like turning on the floating button or the stats. It happened however the menu
  was opened: holding Select, Back, the three-finger tap or the left edge swipe. Android works
  out where the stream shows through only when it lays out the screen, and it did that as the
  menu began to slide in from the right. The menu now keeps the stream out of where it slides to
  as well. Thank you to SippyCupXIII for reporting it
  ([#14](https://github.com/SicarioGaming/Starwire/issues/14)). Checked by JVM tests on a
  1920x1080 screen at 320, 400 and 480 dpi, not yet tried on a device.
- On the Android 11 phone emulators, the PC list and the games screen never let the app go idle,
  so the device tests that open them never finished. A possible cause is fixed. Each time Android
  measured the system bars, these screens and the settings screens set the navigation bar's look
  again, even when nothing had changed, and Android can answer that by measuring again. They now
  set it only when it changes. Whether that was the cause is only known after the next emulator
  run. Checked by JVM tests, not yet tried on a device.
- On Android 5.0 and 5.1, the encrypted microphone's code threw an error on a forged or damaged
  audio packet instead of refusing it. These versions get AES-GCM from Android's old copy of
  BouncyCastle, which reports a bad packet in a different way. Starwire only sends audio, so
  streams weren't affected, but the on-device test failed on Android 5.0. In that same run,
  Android 5.0 matched every line of the shared test vectors. JVM tests now run the shared test
  vectors through a stand-in for how Android 5.0 and 5.1 report a bad packet. The fix hasn't
  been run on Android 5.0 yet, and no test has run on Android 5.1.

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

[Unreleased]: https://github.com/SicarioGaming/Starwire/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/SicarioGaming/Starwire/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/SicarioGaming/Starwire/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/SicarioGaming/Starwire/compare/v0.1.2...v0.2.0
[0.1.2]: https://github.com/SicarioGaming/Starwire/compare/v0.1.0...v0.1.2
[0.1.0]: https://github.com/SicarioGaming/Starwire/releases/tag/v0.1.0
