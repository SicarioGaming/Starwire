# Where the prebuilt files come from

Starwire keeps a few compiled files in the repo instead of building them every time. This page
lists every one of them: where it comes from, its licence, its SHA-256, and how to check it
yourself. You don't have to take my word for any of it. The check builds the files again from
their public upstream source and compares the bytes.

## Check it yourself

From the repo folder (on Windows, in Git Bash):

    sh .github/tools/prebuilts/verify.sh

It fetches the pinned upstream commits from GitHub, builds libadrenotools, its hook libraries
and PyroWave, regenerates the shader header, and compares every result with the committed file
and with [`SHA256SUMS`](../.github/tools/prebuilts/SHA256SUMS). It never changes the repo. On a
desktop PC the libadrenotools part takes about 15 seconds.

It needs git, curl, Python 3 and, from the Android SDK, the exact tools the files were built
with: `sdkmanager "ndk;29.0.14206865" "ndk;27.0.12077973" "cmake;3.31.6"`. The header of
`verify.sh` lists the options, for example `verify.sh adrenotools` to check just that part.

To check the libraries inside an APK you downloaded, for example from a release:

    sh .github/tools/prebuilts/verify.sh --apk Starwire-0.2.0.apk

The app's Gradle build puts these files into the APK untouched, so the copies in a Starwire APK
have the same SHA-256 as the files here. I checked that with the 0.2.0 debug APK, a current
release build and the 0.1.0 APK. To check only the hashes of the committed
files, with no build: `sha256sum -c .github/tools/prebuilts/SHA256SUMS`.

Each file gets one of these results:

| Result | Meaning |
| --- | --- |
| `MATCH` | Identical, byte for byte. |
| `MATCH EXCEPT BUILD ID` | Only the 20-byte build ID differs (see below). Everything else is identical. |
| `SAME CODE` | Everything Android loads from the file is identical. Only the build ID and parts Android never reads, like the name of the compiler, differ. |
| `DIFFERENT` | The code or data differ. |

GitHub runs the same checks ([`prebuilts.yml`](../.github/workflows/prebuilts.yml)) on its
Windows machines whenever a change touches one of these files, and every Monday. Anyone can
read those runs' logs on the repo's Actions tab, once the first one has run.

### Why Windows, and what the build ID is

The libadrenotools files were built on Windows. The Windows NDK's compiler calls itself
`Android (13989888, based on r563880c) clang version 21.0.0` and writes that name into every
library, while the Linux NDK's compiler is a differently optimised build with a longer name. So
a rebuild on Linux, and probably on macOS, can't be byte for byte identical. The most it can show
is `SAME CODE`, and nobody has tried that yet. For a byte for byte check, use Windows, or read
GitHub's run.

The build ID is a hash the linker works out over the whole library before the debug info is
stripped, and the debug info holds the folders the build ran in. So the same source built in
two folders comes out different in those 20 bytes and nowhere else. `verify.sh` maps its own
folders to the names the original build used (`-ffile-prefix-map`), which is why its rebuild of
libadrenotools matches byte for byte.

## What is proven today

Checked on 23 October 2026 on the maintainer's Windows 11 PC, with NDK 29.0.14206865 and CMake
3.31.6, from a fresh fetch of the pinned commits. I ran it twice, the second time with the NDK
seen from a different folder, and got the same result.

| File | Result |
| --- | --- |
| `libadrenotools.so` and its 4 hook libraries | `MATCH`, byte for byte, built from the pinned upstream commits |
| `driver.h`, `priv.h`, `pyrowave.h` | Same as the upstream files |
| `shaders_spv.h` | `MATCH`, regenerated from the shaders in the repo |
| `gradle-wrapper.jar` | `MATCH` with the checksum Gradle publishes for 7.4.2 |
| `libpyrowave-shared.so` | `MATCH EXCEPT BUILD ID` on GitHub with NDK 27.0.12077973 (not installed on my PC). See [PyroWave](#pyrowave-from-moonlight-x) |
| OpenSSL and Opus static libraries | Identical to official Moonlight's files. Not rebuilt here |

The first run of `prebuilts.yml` on GitHub, on 23 October 2026 when this page was published,
gave the same results for everything above and proved PyroWave too: [run 39909763472](https://github.com/SicarioGaming/Starwire/actions/runs/39909763472).

## Every binary file in the repo

### libadrenotools, built for Starwire

[libadrenotools](https://github.com/bylaws/libadrenotools) by Billy Laws, at commit
`8fae8ce254dfc1344527e05301e43f37dea2df80`, with its submodule
[liblinkernsbypass](https://github.com/bylaws/liblinkernsbypass) at
`aa3975893d83ef1bc84c321ec60c65fbf1287887`. Both are BSD-2-Clause. Built for arm64-v8a only
(libadrenotools supports no other Android ABI) by
`app/src/main/jni/adrenotools/build_libadrenotools.sh`, with NDK 29.0.14206865 and CMake 3.31.6
on Windows, and added in commit `2fc931d1`.

| File in `app/src/main/jni/adrenotools/lib/arm64-v8a/` | Bytes | SHA-256 |
| --- | --- | --- |
| `libadrenotools.so` | 862712 | `418635f3a7b4514b04c6b36f91ae6d0ba9918cf50aef51deffc868ca7d42a4f2` |
| `libhook_impl.so` | 251536 | `45f9bc7e66967df974ec18b108ba835d71fd563a3c203581d233eea97140ce9a` |
| `libmain_hook.so` | 4496 | `26caf83da45c170887379572946c94986023462b2bbc968cce2b979543e7b5af` |
| `libfile_redirect_hook.so` | 4296 | `23ed2ae2fb4d640f5d2ce00f126122dd6f4d12084b341589d51d970a3f541670` |
| `libgsl_alloc_hook.so` | 4592 | `3ac23869e8c20757d30108e4613147d67749c21ad71c0f4e30af4c4d2b2ef15d` |

`include/adrenotools/driver.h` and `priv.h` next to them are unchanged copies of the upstream
files. What these libraries do is explained [below](#what-the-custom-driver-hooks-do-and-when-they-run).

### PyroWave, from Moonlight X

[PyroWave](https://github.com/Themaister/pyrowave) by Hans-Kristian Arntzen, at commit
`89f7e47d4abbf650c91fae766728af866c5e32a0` (MIT). It builds with
[Granite](https://github.com/Themaister/Granite) at `1b2d1801d2910fb09ebcded2f0bb3a3a781103b5`
(MIT), which PyroWave pins in its `checkout_granite.sh`, and Granite's submodules
[volk](https://github.com/zeux/volk) at `47cddf7ed97b94118a08aacb548a411188e016cc` (MIT) and
[Vulkan-Headers](https://github.com/KhronosGroup/Vulkan-Headers) at
`6802bb4733b63ed5efd3adb308a6c885ef180ea1` (Apache-2.0 or MIT).

| File | Bytes | SHA-256 |
| --- | --- | --- |
| `app/src/main/jni/pyrowave/lib/arm64-v8a/libpyrowave-shared.so` | 1321464 | `a0796c3c34307511d96e8d1f6cd4116040ce1bf85c28a284e1cb213603558815` |

Starwire didn't build this one. It came with Moonlight X's commit `6ea97f3c`
(MoreOrLessSoftware, 29 September 2026), and it is byte for byte the file Moonlight X shipped
until it moved to PyroWave 1.0 on 20 October 2026 (commit `fd2e0c4b`). The library itself says it was built with NDK r27 (27.0.12077973, clang
18.0.1), even though the build script next to it says it uses the app's NDK, which was already
29 at the time. So that script isn't how this exact file was made, and the folders the build ran
in weren't recorded.

So the best a rebuild can show is `MATCH EXCEPT BUILD ID`, and that's what GitHub's first run
of `prebuilts.yml` got: rebuilt from the pinned commit with NDK 27.0.12077973, every byte is the
same apart from the 20-byte build ID ([run 39909763472](https://github.com/SicarioGaming/Starwire/actions/runs/39909763472)). It also exports the same 44 `pyrowave_*`
functions as a fresh build of the pinned commit, and it contains upstream PyroWave's compiled
shaders byte for byte. `verify.sh pyrowave` rebuilds it with NDK 27.0.12077973; the best that
can come out is `MATCH EXCEPT BUILD ID` or `SAME CODE`, because the original folders are
unknown. That NDK wasn't installed on the PC used for this page, so the PyroWave job in
`prebuilts.yml` checks it on GitHub. If that check ever fails, the honest fix is to rebuild
PyroWave from the pinned commit with the repo's NDK and commit that file instead.

`app/src/main/jni/pyrowave/pyrowave.h` is an unchanged copy of the upstream header.

### OpenSSL and Opus, inherited from Moonlight

These static libraries come unchanged from official
[Moonlight](https://github.com/moonlight-stream/moonlight-android): commit `31b70030` by Cameron
Gutman (2 September 2026), "Update to libopus 1.6.1 and openssl 4.0.2", built by GitHub Actions
from [cgutman/moonlight-mobile-deps](https://github.com/cgutman/moonlight-mobile-deps) at
`419349aa689e8749a9fb91a22dc45531ddd3df45`
([run 33597171234](https://github.com/cgutman/moonlight-mobile-deps/actions/runs/33597171234),
artifact `moonlight-android-deps.zip`, digest
`sha256:354ad373cd160cb18a863e04222a16d13cfa49647c6c1fe3f3d5665ba03efbd8`, which GitHub keeps
until 1 December 2026). Their git blob IDs are the same as on Moonlight's `master` branch, so
they are the same files. OpenSSL is Apache-2.0 and Opus is BSD-3-Clause. `verify.sh` checks
their hashes but doesn't rebuild them.

| File in `app/src/main/jni/moonlight-core/` | Bytes | SHA-256 |
| --- | --- | --- |
| `openssl/arm64-v8a/libcrypto.a` | 11368142 | `d593ba8f576db9ff20d058bee7f0f154b4e000b447548533bc8f16eb95290b54` |
| `openssl/armeabi-v7a/libcrypto.a` | 7475116 | `76abf9210207bb74d19c758e898090f5b4c45fdf1c9a9e8613431c7d9455d904` |
| `openssl/x86/libcrypto.a` | 7860308 | `88699686084a5cf6068be378b86b7e90f9ea646f6aca09b17303d8cdbe933aff` |
| `openssl/x86_64/libcrypto.a` | 11403206 | `8502529fe9992e4f67962a5c5d98139ec37cd9ccacac341622500bf399ad52ca` |
| `opus/arm64-v8a/libopus.a` | 2933886 | `d6c39b9135017ab76a42e364569f18622e6e3df691ac2d1d9d36ee4a02eb2e13` |
| `opus/armeabi-v7a/libopus.a` | 2130614 | `3925fecb62e4a75095d658bcc1c4e309712a330699825b7790880cf21497719e` |
| `opus/x86/libopus.a` | 2170094 | `ed39b86eee0868d911516c4034e8395af31aaa7bfb8ac7e4415846b59ee28d1f` |
| `opus/x86_64/libopus.a` | 3142594 | `7d8214860fdab5a8bf0d15125a2ae7baf80169dc4e6ee1ffa2633bb2e7de7a8b` |

### The Gradle wrapper

| File | Bytes | SHA-256 |
| --- | --- | --- |
| `gradle/wrapper/gradle-wrapper.jar` | 59821 | `575098db54a998ff1c6770b352c3b16766c09848bee7555dab09afc34e8cf590` |

The official wrapper jar of Gradle 7.4 to 7.4.2 (Apache-2.0), added to Moonlight in 2022
(commit `8f31aa59`). Gradle publishes its checksum at
`https://services.gradle.org/distributions/gradle-7.4.2-wrapper.jar.sha256`, and both
`verify.sh` and Gradle's own wrapper check in `prebuilts.yml` compare against it. All it does is
download Gradle 9.7.1, the version in `gradle-wrapper.properties`.

### Generated: shaders_spv.h

`app/src/main/jni/vulkan/shaders_spv.h` is compiled SPIR-V for the Vulkan renderer, made from
the GLSL files in `app/src/main/jni/vulkan/shaders/` by `compile_shaders.sh` with the `glslc`
in NDK 29.0.14206865. `verify.sh shaders` regenerates it in a temporary folder and compares.
It's a text file, so its hash depends on line endings and it isn't in `SHA256SUMS`.

### Pictures

The PNG, JPG and ICO files (the app icons and TV banner, the screenshots in `docs/images`, and
the Windows microphone receiver's icon) are pictures, not code, and aren't listed. `verify.sh`
fails if any other binary file is added to git without a line in `SHA256SUMS`.

## What the custom driver hooks do, and when they run

### What it's for

The "Custom Vulkan driver" option lets PyroWave use a Vulkan driver you supply, such as Mesa's
Turnip for Adreno GPUs, instead of the one that came with the phone. libadrenotools is what
makes that possible.

### When it runs

It's **off by default** (Settings > Advanced > Custom Vulkan driver). libadrenotools
is only opened when all of these are true:

- you turned the option on and picked a driver file;
- the phone is 64-bit ARM (arm64-v8a) with Android 10 or newer;
- the file starts like a 64-bit ARM shared library;
- a PyroWave stream is starting, or Starwire is checking whether PyroWave can run.

Otherwise none of these five libraries is ever loaded. They are inside every arm64 APK, but
with the option off they just sit there. Other codecs never use them, even with the option on.

### What happens when it runs

1. Opening `libadrenotools.so` runs liblinkernsbypass's start-up code. Android keeps each app's
   libraries in a fenced-off "namespace", and won't let an app load its own copy of system
   libraries such as the Vulkan loader. To get round that, liblinkernsbypass makes the memory
   page holding the system's `dlopen` writable and executable, reads its machine code to find
   the system linker's internal function, and calls that as if the call came from the system
   itself. That gives it the linker's private functions for making namespaces.
2. libadrenotools makes a private namespace and loads `libhook_impl.so` and `libmain_hook.so`
   into it, from Starwire's own library folder.
3. It loads a separate copy of the phone's `/system/lib64/libvulkan.so` into that namespace.
   On Android 10 and newer the copy only exists in memory.
4. `libmain_hook.so` replaces `android_dlopen_ext` for that copy only. When it asks for the
   phone's GPU driver (a library whose name contains `vulkan.`), it gets the file you picked
   instead.
5. Only PyroWave's renderer uses that copy. The rest of Starwire and of Android keep the
   system driver.

`libfile_redirect_hook.so` and `libgsl_alloc_hook.so` serve two other libadrenotools features
(redirecting the driver's file access, and importing memory into the GPU). Starwire never asks
for them, so they are never loaded. They're shipped because they come out of the same build.

### What it means for you

A driver you load is native code that runs inside Starwire with all of Starwire's permissions:
the network, the microphone, and the key Starwire uses to talk to your paired PCs. Only use a
driver from a source you trust. If the driver fails to load or can't run PyroWave, Starwire
uses the system driver. If it crashes, the stream closes.

## Built from source with the app: the USB exporter

The USB exporter (`libmoonlight_usbip.so`, from Moonlight V+'s usbip-backend) isn't a prebuilt
file: the build compiles it every time. Its first build downloads four source archives from
GitHub at fixed revisions, and CMake refuses any whose SHA-256 differs from the one in
`usbip-backend/src/main/cpp/CMakeLists.txt`. `UsbBuildTest` checks that each one is pinned that
way.

| Source | Revision | Licence |
| --- | --- | --- |
| [usbipdcpp](https://github.com/yunsmall/usbipdcpp) | commit `1355113f030c4c13404030e6e7426bceb6085276` | LGPL-3.0 |
| [libusb](https://github.com/libusb/libusb) | tag `v1.0.29` | LGPL-2.1-or-later |
| [Asio](https://github.com/chriskohlhoff/asio) | tag `asio-1-30-2` | BSL-1.0 |
| [spdlog](https://github.com/gabime/spdlog) | tag `v1.15.3`, with fmt inside it | MIT |

The build changes one file of usbipdcpp before compiling it, V+'s connection filter
(`patch_usbipdcpp.cmake`), and stops if the pinned file no longer matches the patch. V+'s own
test certificates and keys for its tunnel to Sunshine weren't brought in, since Starwire doesn't
use that tunnel.

## Changing one of these files

Change the file, its pin in `verify.sh` and its line in `SHA256SUMS` in the same commit, and
run `verify.sh` before committing: it has to say `MATCH` again. The line for a file comes from
`sha256sum <file>`, run from the repo folder.

Today's hook libraries only match byte for byte because `verify.sh` knows the folders the
original build used. Linking with `-Wl,-s` leaves the folders out of the build ID, so any folder
on any machine gives the same bytes; a future rebuild should do that, and then the folder
mapping in `verify.sh` can go.
