# The USB exporter (usbip-backend)

This module is Moonlight V+'s Android USB/IP exporter, by qiin2333 and the V+ contributors
([qiin2333/moonlight-vplus](https://github.com/qiin2333/moonlight-vplus), GPL-3.0), brought into
Starwire with `git cherry-pick -x` so each change keeps its author. It builds usbipdcpp (yunsmall,
LGPL-3.0) and libusb into the app from pinned sources, and shares one Android USB device as a
USB/IP server. Its way of handing Android's file descriptor to libusb follows yunsmall's
[Android-Usbipdcpp](https://github.com/yunsmall/Android-Usbipdcpp). V+'s own README, with V+'s
tests on its devices, is at
[e8f69de0](https://github.com/qiin2333/moonlight-vplus/blob/e8f69de08c104c97c95a0db3b5c9a3ca58506054/usbip-backend/README.md).

This product uses usbipdcpp (https://github.com/yunsmall/usbipdcpp), licensed under LGPLv3.

## What Starwire took, and what it changed

- **Taken:** the CMake build of usbipdcpp, libusb, Asio and spdlog at fixed revisions with their
  SHA-256, V+'s patch to usbipdcpp's accept loop, the JNI layer (`usbip_jni.cpp`, `NativeUsbIp`),
  `UsbIpBackend` and the device tests.
- **Left out:** V+'s TLS tunnel to Sunshine (`UsbReverseTunnel`), its test certificates and keys,
  the diagnostics app and V+'s Kotlin stream menu. Starwire carries the device's traffic through
  its own encrypted tunnel to its own PC helper ([usb-protocol.md](../docs/dev/usb-protocol.md)).
- **Changed for Starwire:** the app's NDK and minimum Android version, the C++ library linked in
  statically, 64-bit ARM only in a release (x86_64 too in a debug build, for the emulators' tests),
  no ARMv7, and CMake 3.31.6. `UsbIpBackend.isSupported` goes by the process's own instruction set,
  so an x86_64 device that runs ARM apps isn't offered an exporter its APK doesn't have. The two
  usbipdcpp files the build changes say so at their top. Two native calls are Starwire's own: the
  device's speed and a HID interface's report descriptor, which Starwire's device rules read
  before anything is exported.
- **Starwire's own code on top:** `UsbExporter`, `UsbDeviceRules` and `UsbPermission` in
  `app/src/main/java/com/limelight/binding/usb/`. They share one device at a time, after the
  device rules, through one exporter that listens only while that device is shared.

## Only the connection Starwire authorised

The exporter listens on 127.0.0.1, and every app on the phone can reach 127.0.0.1. So, as in V+,
Starwire binds its own socket to a free local port first, authorises that exact source port in
native code, then connects. The patched accept loop uses the authorisation once and closes every
other connection. Starwire also takes it only from 127.0.0.1, not the rest of 127.0.0.0/8, which
is the phone too and where another app could bind the same port number. Starwire's socket has
`SO_REUSEADDR` off, so on Linux no other socket can bind the same port on 127.0.0.1 while it's
authorised. A JVM test checks the option is off, and a device test checks a connection from
127.0.0.2 is closed, but neither has been tried against another app on a device.

## Tests

- `./gradlew testNonRootDebugUnitTest` runs this module's JVM tests too
  (`UsbIpBackendSupportTest`), with the app's `UsbBuildTest`, `UsbDeviceRulesTest`,
  `UsbExporterTest` and `UsbPermissionTest`.
- `./gradlew assembleNonRootDebugAndroidTest` builds this module's device tests too
  (`NativeUsbIpTest`, `UsbIpBackendLifecycleTest`, from V+). The app's own device test,
  `UsbExporterLoopbackTest`, runs the real exporter on 127.0.0.1 with no USB device.
- Tried: on 16 November 2026, the app's `UsbExporterLoopbackTest` passed on a RedMagic Astra
  tablet (Android 16). The same day, a DualSense plugged into that tablet by cable was shared
  through this exporter with a Windows 11 PC, and used there. In the emulator run on 27 October,
  the same test passed on 14 of the 17 emulators. The two Android TV emulators are 32-bit x86,
  which has no exporter library, so they skipped it. One emulator stopped early, before it ran.
- Not tried yet: V+'s own device tests (`NativeUsbIpTest`, `UsbIpBackendLifecycleTest`), which
  no script runs, and any USB device but a DualSense.
