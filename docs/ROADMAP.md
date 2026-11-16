# Starwire roadmap

Starwire is a Moonlight Android client for streaming from a Vibepollo host to a
RedMagic Astra tablet (Snapdragon 8 Elite, Android 16) with a GameSir G8+. It keeps Moonlight X's video
pipeline (Direct Frame Pacing, PyroWave, Vulkan renderer) and adds Artemide's
options and in-stream menu, selected features from other forks, and a new UI.

Base: Moonlight X (`MoreOrLessSoftware/moonlight-android`, branch `mlsoft-x`).
Work happens on branches and is merged into `main`. Every source below is GPL-3.0, so
Starwire is GPL-3.0 too.

Each step ends with a build installed on the tablet, and many come with on-device tests in
`app/src/androidTest`. Anything marked "not yet tried" below hasn't been checked on real hardware.

## Phase 1: Identity

- [x] Rename the app to "Starwire" (`com.starwire`, debug builds `com.starwire.debug`),
      so it installs beside Moonlight X and Artemide instead of replacing them.

## Phase 2: Artemide features

Source: Artemide Experimental Async 0.5.1 (`derflacco/moonlight-android`, tag
`Artemide_Experimental_Async_0.5.1`), the version installed on the tablet.

- In-stream menu. Artemide's actions are added to Moonlight X's overlay menu
  (icon buttons, controller navigation) rather than Artemide's plain list dialog.
  - [x] Floating menu button: tap to open, drag to move; on by default, with a
        setting under Overlay Menu and a show/hide button in the menu
  - [x] Shortcuts page with Artemide's 14 key combos (Esc, F11, Alt+F4, Alt+Enter,
        Ctrl+V, Win, Win+D, Win+G, Ctrl+Alt+Tab, Shift+Tab, Win+Shift+Left,
        Ctrl+Alt+Shift+F1, Ctrl+Alt+Shift+F12, Win+Alt+B)
  - [x] Task Manager, Rotate screen, On-screen controller toggle
  - [x] Disconnect, Quit session, Stats, Soft keyboard, Mouse emulation (already in Moonlight X)
  - [x] Pan/zoom mode (pinch to zoom, drag to pan, double-tap to reset)
  - [x] Touch mode picker with Artemide's six modes (multi-touch, direct touch, natural and
        gaming trackpad, disabled, swapped clicks); starts from the trackpad setting each stream
  - [x] Touch sensitivity: a trackpad sensitivity setting, toggled from the menu
  - [x] Clipboard send/get and PC (server) commands. Vibepollo supports both
        (`/actions/clipboard`, packet 0x3000); each needs the client's permission in Vibepollo.
        Server commands needed a change to moonlight-common-c, committed to Starwire's fork
        (`SicarioGaming/moonlight-common-c`)
  - [x] On-screen full keyboard (Full Keyboard in the menu): Artemide's 82-key layout, key
        popups, vibration and sticky Ctrl/Alt/Shift/Win, with opacity and vibration settings.
        Keys go straight to the host as keyboard input
  - [x] Automatic clipboard sync (Host Settings): sends this device's clipboard when the stream
        starts or you return to it, copies the PC's when you leave (also before Disconnect and
        Quit Session, waiting up to 2 seconds); optional messages and "hide clipboard content"
  - Not ported: Artemide's customisable on-screen key buttons (KeyBoardController, ~3,000 lines)
  - [x] Custom shortcuts imported from a file, in Artemide's format (`{"data": [{"name": ...,
        "keys": ["VK_LWIN", "0x53"]}]}`), from Settings > In-stream menu. They appear on the
        Shortcuts page after the built-in ones; importing again replaces them, and a bad file
        says what's wrong and keeps the earlier import. Example: `docs/shortcuts-example.json`.
        (Moonlight X's custom commands still cover adding your own by hand)
- [x] Virtual Display: a "Virtual display" setting (host default, on, off) and
      "Start in Virtual/Primary Display" when long-pressing a game. Host default sends nothing,
      because Vibepollo treats `virtualDisplay=0` as turning its virtual display off
- [x] Prevent Packet Loss: a tiny ignored control packet every 20 ms keeps Wi-Fi awake
      (new `LiSendKeepAlivePayload` in common-c)
- [x] V-Sync and Fast V-Sync: one "V-Sync" setting (Off, V-Sync, Fast V-Sync), off by
      default and used only by the direct renderer. Fast V-Sync is Artemide's nano pacer
      (NanoPacer.java) in "Prefer lowest latency" pacing. Artemide's own V-Sync only drives
      its GL upscaler, which Starwire doesn't have, so V-Sync here holds each frame for the
      next display refresh instead
- [x] Sync: LFR: off by default; in Balanced pacing keeps only the newest decoded frame
      (the other pacing modes already drain to the newest frame)
- [x] Async decode and Async: LFR, both off by default (Async LFR needs Async decode).
      Artemide's AsyncCodecAdapter, used only by the direct renderer without host frame
      timing. Codec recovery follows Artemide: release queued buffers, start() after flush,
      detach callbacks before stop()/reset() and attach them again before configure().
      Tested against the device's real H.264 decoder, including all three recovery paths
- Untouched by all of the above: the Vulkan renderer, host frame timing pacing, PyroWave
- [x] Lite mode performance overlay: one centered line with bandwidth, network/decode delay,
      loss and FPS (Advanced settings, under "Show performance stats")
- [x] Check Moonlight X's existing Ultra Low Latency and Force Full Range Video
      against Artemide's behaviour (2026-10-21, against Artemide's `MediaCodecHelper` and
      `MediaCodecDecoderRenderer` at the 0.5.1 tag)
  - Force Full Range: the same in both (asks the PC for full range, and tells the decoder
    with `KEY_COLOR_RANGE`); Starwire also passes it to the Vulkan renderer. No change
  - Ultra Low Latency: the same for Snapdragon (like the tablet's 8 Elite), Exynos and
    decoders with Android's own low-latency support. Ported the two differences:
    MediaTek's `vendor.mtk.vdec.ultra-low-latency` now follows the setting (Moonlight X
    always set it to 0), and low-latency is set again after the decoder starts (Android 11+)
  - Left as Moonlight X's: Artemide's other MediaTek queue/preload values and its NVIDIA
    Tegra and legacy OMX Qualcomm extras. They aren't part of this setting, and there's no
    such device here to try them on

## Phase 3: Features from other forks

| Feature | Source fork |
| --- | --- |
| More ways to open the menu (long-press). Three-finger tap and holding Select, Start, Guide or LB+RB already work in Moonlight X | Moonlight X Turnip (`NoCrypt/moonlight-x-turnip`) |
| Per-game settings (resolution, FPS, bitrate, frame pacing) | Moonlight X Turnip |
| Custom PyroWave Vulkan driver (Turnip via libadrenotools) | Moonlight X Turnip |
| Better performance stats (1% lows, received vs displayed FPS warning) | Moonlight V+ (`qiin2333/moonlight-vplus`), Moonlight X Turnip |
| Change bitrate mid-stream from the menu | Moonlight V+ |
| Audio haptics (bass becomes vibration) | Moonlight V+ |
| Pinch-to-zoom anytime, accurate taps while zoomed | Moonlit Artemis (`4had45/moonlit-artemis`) |
| Monitor switcher | Moonlit Artemis |

Done so far (2026-10-21):

- [x] Monitor switcher: Monitors page in the menu, Monitor 1-6 send Ctrl+Alt+Shift+F1-F6
- [x] Bitrate mid-stream: Bitrate in the menu, 5-500 Mbps, via Vibepollo `/bitrate`
- [x] Better stats: 1% low FPS (average of the slowest 1% of frame gaps) in both overlays
- [x] Pinch to zoom anytime (Input settings, on by default; not in Multi-touch mode)
- [x] Per-game settings: App Settings (long-press a game) can also set the video codec, V-Sync,
      Sync: LFR, Async decode, Async: LFR, Virtual display, touch mode, Hide PC cursor and
      Audio haptics for that game or Quick Launch item; each defaults to the global setting
- [x] Audio haptics (Audio settings, off by default): bass below ~120 Hz drives this device's
      vibration and/or the first controller's heavy motor, with a strength setting. The tablet's
      motor is on/off only, so strength becomes pulse length there. Game rumble and audio
      haptics share the controller (the stronger wins)
- [x] Custom PyroWave driver (Advanced settings, experimental, off by default): ported from
      Moonlight X Turnip (`0703b360`). Pick a Turnip .so/.zip/.adpkg; only PyroWave uses it. The
      libadrenotools and hook libraries in the repo were built from the pinned upstream commit
      (`adrenotools/SOURCE`) rather than copied from the fork's prebuilts. Added: files that
      aren't 64-bit ARM libraries are refused, and a driver that can't load or can't run
      PyroWave falls back to the system driver (tested with a broken file only). No speed
      measurements. Not yet tried with a real Turnip driver

Also added on request: Warp Drive / Warp 2 frame pacing (from Artemide), Hide PC cursor
(never / while touching / always), and Long-press to right-click in the gaming trackpad mode.

### Phase 3 research notes (2026-10-21)

- Per-game settings already exist in Moonlight X (long-press a game, App Settings: resolution,
  FPS, bitrate, frame pacing, HDR, stats; plus Quick Launch). Remaining: add Starwire's new
  options (V-Sync, LFR, async decode, touch mode) to it.
- Bitrate mid-stream: Vibepollo `GET /bitrate?bitrate=<kbps>` (HTTPS, any streaming client),
  clamps to the host maximum and returns the applied value in `root.bitrate`.
- Monitor switcher: Vibepollo switches display on Ctrl+Alt+Shift+F1..F13 (input.cpp,
  `switch_display`), so a menu page sending those keys is enough.
- Better stats: Moonlight X already shows received vs rendered FPS variance; add 1% lows.
- Pinch-to-zoom anytime: detect a two-finger pinch (finger span changes) outside pan/zoom mode
  and hand that gesture to PanZoomHandler; not in multi-touch mode.
- Custom PyroWave driver: NoCrypt commit `0703b360` (remote `turnip`), 22 files, ~800 lines plus
  libadrenotools arm64 prebuilts and native Vulkan loader changes. Port it with
  `git cherry-pick`-style care, since our base has newer Vulkan renderer code.
- Audio haptics: new; low-pass the decoded audio, drive the device vibrator or controller rumble.

## Phase 4: Redesign

- [x] Mockups of the new look to choose from before building (2026-10-21). Chosen: **B · Cover
      Glow**: the screens take their colour from the selected game's cover art, frosted panels,
      a big "last played" hero with that game's settings as chips, plain-language settings with a
      "What you'll feel" panel, and the in-stream menu as a frosted side sheet tinted by the game
- [x] Cover Glow game screen: hero for the selected game tinted by its cover, cover row, PC list
      with frosted cards (renders in `docs/images/cover_glow_home.png`,
      `docs/images/cover_glow_pcs.png`)
- [x] Cover Glow settings: back button and title, a pill per section that jumps to it and
      follows the scroll, teal headings, white rows; per-game settings share the look. The PC
      list's overrides bar uses frosted pills (`docs/images/cover_glow_settings.png`)
- [x] Settings: plain-language section names (English only; other languages keep their
      translated headings), and a "What you'll feel" panel on wide screens that places the frame
      pacing and V-Sync choices between fastest response and smoothest motion, with rough
      delay and battery comparisons (not measurements)
- [x] Cover Glow in-stream menu: a side sheet on the right in the game's cover colour, with
      the game and stream on top, a grid of tools, and Close, Quit and Disconnect at the bottom;
      controllers move by rows and columns (`docs/images/cover_glow_menu.png`)
- [x] Icon tiles for apps without cover art (Desktop, Steam Big Picture, Playnite and so on),
      chosen under Settings → App → App covers; a flowing rainbow on the wordmark and the selected
      game's ring; a faint dot grid and HUD-style tags for a gamer feel
- [x] Predicted impact lists what's moving it (frame pacing, V-Sync, Async LFR) and which way;
      settings taken from another fork show a quiet credit line under the panel when tapped
- [x] In-stream menu: five tiles across with Full Keyboard next to Keyboard, a nearly opaque
      panel, the selected row scrolled into view, and a bigger floating button
- [x] Larger Starwire wordmark on the home screen
- [x] Tried on the RedMagic Astra over a live stream: the menu tools work; the last row of the
      menu was hidden by a layout bug, now fixed and covered by a test
- [x] After launch feedback: a smaller floating button that stays faded until touched; on/off
      menu tools show a small switch and keep the menu open; a Starwire tile (logo over a
      starfield) for Desktop and games without cover art, also in the menu's header; Lite stats
      in plain words (Mbps, Ping, Decode, Dropped, FPS)
- [x] Microphone from the device to the PC, ported from logabell's design (a separate audio
      channel). Only works with logabell's Apollo build on the PC
- [x] Microphone with any host (Vibepollo, Sunshine, regular Apollo): a VBAN stream to Starwire's
      own receiver on the PC, which plays it into VB-Cable; one setup script installs both. Not
      encrypted or authenticated in 0.2.0; encrypted, and only from paired devices, from 0.2.1
      (below)
- [ ] Microphone receiver for macOS and Linux (the Windows one is in `windows/`)
- [ ] Microphone over the stream itself with Vibepollo or regular Apollo: waiting for either to
      accept logabell's design
- [x] Narrow screens and foldables: the settings list kept no width on narrow screens (a Galaxy Z
      Fold's cover screen); the game screen and the stream menu now fit small screens too, with
      on-device layout tests at phone and foldable sizes (the tablet's display resized with
      `adb shell wm size`)

## 0.2.1: after the 0.2.0 code review

- [x] GitHub repo cleanup, after
      [u/rambo3349's code review](https://www.reddit.com/r/MoonlightStreaming/comments/1xd12x5/starwire_020_mic_passthrough_wo_needing_to_modify/19rigium2zrt0/)
      in the 0.2.0 Reddit thread. The public repo now carries the real history, every commit with
      its own description and sharing history with Moonlight X, so upstream changes can be merged
      again. Before, it had one commit per folder with the same few messages. The switch on
      23 Oct was a plain fast-forward, so clones, forks and the release tags kept working; an old
      account name and the PC's name were taken out of the maintainer's commits first. Also:
      every prebuilt library is traced to its source and rebuilt byte for byte on GitHub
      (`docs/PROVENANCE.md`, `prebuilts.yml`), the 0.2.0 mic measurements are published with
      their raw data and a script that recomputes them (`docs/measurements/`), the README and the
      release pages say plainly which features are experimental, `SECURITY.md` (with private vulnerability
      reporting) and `CONTRIBUTING.md` were added, and `.github/tools/check-publish.sh` keeps
      secrets and personal details out of anything pushed to the public repo. This part is
      already live; it didn't need a release
- [x] Encrypted microphone (SWMC version 1, `docs/dev/mic-protocol.md`): each device pairs with
      the PC once with a 16-character code from the receiver's **Pair a device...**, and in
      Starwire **Pair with the PC**. Each stream gets its own AES-256-GCM key, and the receiver
      refuses devices it hasn't paired, replayed, late and forged packets, and plain VBAN unless
      it's set up with `-AllowUnencrypted`. A "Not encrypted" option stays in the app for
      Voicemeeter and other VBAN apps, and 0.2.0's default moves to the encrypted receiver once.
      The receiver now plays through WASAPI, with waveOut if WASAPI doesn't work. Updating from
      0.2.0 needs both sides and pairing once. Tested: the app and the receiver agree on a shared
      vectors file (`SwmcCodecTest`, `windows/tests/run-tests.ps1`); a loopback run on one PC
      (`windows/tests/e2e-loopback.ps1`) played every packet in order and refused a replayed, a
      late, two forged and one plain VBAN packet; encrypting took about 6.5 microseconds per
      packet in a benchmark on the tablet; and on 23 Oct a real stream from the tablet to the PC
      over Wi-Fi, with a receiver that only takes encrypted audio, had sound only while the
      stream ran. Dropouts weren't counted in that test
- [x] Custom PyroWave driver: the setting says it's experimental, and the first time it's turned
      on, a warning says what a driver file can do and asks first. Imports are checked (a plain
      file name, at most 64 MB, a 64-bit ARM library, or an archive of at most 64 files holding
      one) and replace the earlier driver; a refused file keeps it. The SHA-256 is recorded at
      import, shown under the setting and checked before every load, and only files inside
      Starwire's driver folder are loaded (a symlink out of it is refused). Archives unpack into
      a folder named after their SHA-256, which fixes a different archive with the same library
      name reusing the old library. The driver can be removed, and it's left out of backups.
      JVM tests in `CustomVulkanDriverImportTest`, device tests in `CustomVulkanDriverTest`. A
      driver imported by 0.2.0 has no recorded SHA-256, so it has to be chosen again. Still not
      tried with a real Turnip driver
- [x] Async decode's flush recovery never hands out a buffer from before the flush. One the
      codec offered just before `flush()` was handed out after `start()` once, on the small
      phone emulator, and failed with "index 0 is not owned by client". JVM tests in
      `AsyncCodecFlushTest`; `AsyncDecodeTest` passed its flush recovery 25 times in a row on
      the RedMagic tablet
- [x] With animations off, the game screen's endless animations (the glow, the floating
      cover, the rainbow, the ring round the chosen game, the stars) stay still. Android 5 to
      7.1 ran them every frame, so the screen never stopped redrawing and the Android 6
      emulator's tests hung. Before Android 8 they also stop, not pause, while the screen is out
      of sight, since a paused one there still wakes the CPU every frame. JVM tests in
      `LoopsTest`. Not yet tried on a real Android 5 to 7.1 device or rerun on the emulator
- [x] The in-stream menu's tools grid fades out at an edge with more tools to scroll to (a
      phone, a Fold's cover screen, a small handheld), and the controller's selection stops
      clear of the fade. Checked at every screen size in `ActivityScreensTest`, and in
      `GridScrollTest`

## Open questions

- Artemide has no option named "Tight Vsync"; its V-Sync options are V-Sync,
  Fast V-Sync, Sync: LFR and Async: LFR, and all four are planned above.
- Clipboard sync, server commands and Virtual Display were built for Apollo;
  check each one against Vibepollo while testing.
