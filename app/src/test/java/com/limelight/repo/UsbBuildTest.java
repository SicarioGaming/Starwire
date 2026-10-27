package com.limelight.repo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.RepoFiles;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The USB exporter's module (usbip-backend, from Moonlight V+) is built the way the app is: the
 * app's NDK and minimum Android version, the C++ library linked in statically, 64-bit ARM in a
 * release and x86_64 too in a debug build, and a CMake that CI installs. Its native sources are
 * fetched at fixed revisions with their hashes. No device needed.
 */
public class UsbBuildTest {
    private static final String MODULE = "usbip-backend/build.gradle";

    private static String one(String text, String regex, String what) {
        Matcher m = Pattern.compile(regex, Pattern.MULTILINE).matcher(text);
        assertTrue(what + " not found", m.find());
        String found = m.group(1);
        assertFalse(what + " is there twice", m.find());
        return found;
    }

    @Test
    public void theModuleUsesTheAppsNdkAndMinimumAndroidVersion() throws Exception {
        String app = RepoFiles.read("app/build.gradle");
        String module = RepoFiles.read(MODULE);
        assertEquals(one(app, "ndkVersion \"([^\"]+)\"", "the app's ndkVersion"),
                one(module, "ndkVersion ['\"]([^'\"]+)['\"]", "the module's ndkVersion"));
        assertEquals(one(app, "^\\s*minSdk (\\d+)", "the app's minSdk"),
                one(module, "^\\s*minSdk (\\d+)", "the module's minSdk"));
    }

    // Two copies of libc++_shared.so would clash, and the app's own native code links it statically
    @Test
    public void theModuleLinksTheCppLibraryStaticallyLikeTheApp() throws Exception {
        String mk = RepoFiles.read("app/src/main/jni/Application.mk");
        assertEquals("c++_static", one(mk, "^APP_STL := (\\S+)", "Application.mk's APP_STL"));
        String module = RepoFiles.read(MODULE);
        assertEquals("c++_static", one(module, "-DANDROID_STL=([^'\"]+)", "the module's ANDROID_STL"));
    }

    @Test
    public void releasesCarry64BitArmOnlyAndDebugBuildsAddX86_64ForTheEmulators() throws Exception {
        String module = RepoFiles.read(MODULE);
        assertEquals("'arm64-v8a', 'x86_64'",
                one(module, "debug \\{\\s*ndk \\{ abiFilters (.+?) \\}", "the debug ABIs"));
        assertEquals("'arm64-v8a'", one(module, "release \\{(?:\\s*//[^\\n]*)*\\s*ndk \\{ abiFilters (.+?) \\}",
                "the release ABIs"));
        assertFalse("ARMv7 isn't built", module.contains("armeabi-v7a"));
    }

    // CI's workflows read the CMake version with the same grep: one line, starting "version"
    @Test
    public void everyWorkflowThatBuildsTheAppInstallsTheModulesCmake() throws Exception {
        String module = RepoFiles.read(MODULE);
        String cmake = one(module, "^\\s*version \"([^\"]+)\"", "the module's CMake version");
        assertTrue(cmake, cmake.matches("\\d+\\.\\d+\\.\\d+"));
        String read = "CMAKE=$(grep -oP '^\\s*version \"\\K[^\"]+' usbip-backend/build.gradle)";
        String install = "\"ndk;$NDK\" \"cmake;$CMAKE\"";
        List<String> missing = new ArrayList<>();
        for (String workflow : new String[] {"build.yml", "devices.yml", "release.yml"}) {
            String yml = RepoFiles.read(".github/workflows/" + workflow);
            assertTrue(workflow + " doesn't install the NDK", yml.contains("\"ndk;$NDK\""));
            if (!yml.contains(read) || !yml.contains(install)) {
                missing.add(workflow);
            }
        }
        assertTrue("workflows that don't install the module's CMake: " + missing, missing.isEmpty());
    }

    @Test
    public void theAppBuildsAndTestsTheModule() throws Exception {
        assertTrue(RepoFiles.read("settings.gradle").contains("include ':usbip-backend'"));
        String app = RepoFiles.read("app/build.gradle");
        assertTrue(app.contains("implementation project(':usbip-backend')"));
        // gradlew testNonRootDebugUnitTest lintNonRootDebug assembleNonRootDebugAndroidTest covers it
        assertTrue(app.contains("task.dependsOn ':usbip-backend:testDebugUnitTest'"));
        assertTrue(app.contains("task.dependsOn ':usbip-backend:lintDebug'"));
        assertTrue(app.contains("task.dependsOn ':usbip-backend:assembleDebugAndroidTest'"));
    }

    // Each native dependency is a fixed GitHub archive with its SHA-256, never a moving branch
    @Test
    public void everyNativeSourceIsPinnedByItsHash() throws Exception {
        String cmake = RepoFiles.read("usbip-backend/src/main/cpp/CMakeLists.txt");
        Matcher m = Pattern.compile("FetchContent_Declare\\((\\w+) TIMEOUT \\d+ URL (\\S+)\\s+URL_HASH SHA256=([0-9a-f]+)\\)")
                .matcher(cmake);
        List<String> names = new ArrayList<>();
        while (m.find()) {
            names.add(m.group(1));
            assertTrue(m.group(1) + ": " + m.group(2),
                    m.group(2).matches("https://codeload\\.github\\.com/[\\w.-]+/[\\w.-]+/tar\\.gz/"
                            + "([0-9a-f]{40}|refs/tags/[\\w.-]+)"));
            assertEquals(m.group(1) + "'s hash", 64, m.group(3).length());
        }
        assertEquals(List.of("usbipdcpp", "libusb", "asio", "spdlog"), names);
        assertEquals("every FetchContent_Declare is pinned", names.size(),
                cmake.split("FetchContent_Declare\\(", -1).length - 1);
    }

    // Settings > About > Open-source licences names the versions the exporter is built from
    @Test
    public void theLicencePageNamesThePinnedVersions() throws Exception {
        String cmake = RepoFiles.read("usbip-backend/src/main/cpp/CMakeLists.txt");
        String usbipdcpp = one(cmake, "usbipdcpp/tar\\.gz/([0-9a-f]{40})", "usbipdcpp's commit");
        assertTrue(usbipdcpp.startsWith(notice("usbipdcpp").version));
        assertEquals(one(cmake, "libusb/tar\\.gz/refs/tags/v([\\d.]+)", "libusb's tag"), notice("libusb").version);
        assertEquals(one(cmake, "asio/tar\\.gz/refs/tags/asio-([\\d-]+)", "Asio's tag").replace('-', '.'),
                notice("Asio").version);
        assertEquals(one(cmake, "spdlog/tar\\.gz/refs/tags/v([\\d.]+)", "spdlog's tag"), notice("spdlog").version);
        for (String name : new String[] {"Moonlight V+ USB exporter (usbip-backend)", "usbipdcpp", "libusb", "Asio",
                "spdlog", "fmt (inside spdlog)", "LLVM libc++"}) {
            java.lang.reflect.Field f = com.limelight.ui.OpenSourceNotices.Component.class.getDeclaredField("libraries");
            f.setAccessible(true);
            assertTrue(name + " doesn't list libmoonlight_usbip.so",
                    List.of((String[]) f.get(notice(name))).contains("libmoonlight_usbip.so"));
        }
    }

    // usbipdcpp asks for this line where people will see it: it heads its licence on that page
    @Test
    public void usbipdcppsAttributionLineIsShown() throws Exception {
        String line = "This product uses usbipdcpp (https://github.com/yunsmall/usbipdcpp), licensed under LGPLv3.";
        String text = RepoFiles.read("app/src/main/assets/licenses/" + notice("usbipdcpp").asset).replace("\r", "");
        assertTrue(text.startsWith(line + "\n"));
        assertTrue(text.contains("GNU LESSER GENERAL PUBLIC LICENSE"));
    }

    // The build changes two of usbipdcpp's files, and each says so at its top, with who changed it
    // and when, as the LGPL-3.0 asks (through GPL-3.0 section 5a)
    @Test
    public void theUsbipdcppFilesTheBuildChangesSaySo() throws Exception {
        String patch = RepoFiles.read("usbip-backend/src/main/cpp/patch_usbipdcpp.cmake");
        List<String> writes = new ArrayList<>();
        Matcher m = Pattern.compile("file\\(WRITE \"([^\"]+)\" \"([^\"]+)\"\\)").matcher(patch);
        while (m.find()) {
            writes.add(m.group(1) + " " + m.group(2));
        }
        assertEquals(List.of(
                "${patched_include}/Server.h ${notice_start}include/usbipdcpp/Server.h${notice_end}${patched_header}",
                "${patched_root}/Server.cpp ${notice_start}src/Server.cpp${notice_end}${patched_source}"), writes);
        String start = one(patch, "set\\(notice_start \"(.+)\"\\)", "the notice's start");
        String end = one(patch, "set\\(notice_end \"(.+)\"\\)", "the notice's end");
        assertEquals("// Changed from ", start);
        for (String part : new String[] {"usbipdcpp (https://github.com/yunsmall/usbipdcpp)", "LGPL-3.0",
                "Moonlight V+ (qiin2333)", "8 Sep 2026", "patch_usbipdcpp.cmake"}) {
            assertTrue("the notice doesn't say " + part, end.contains(part));
        }
        // The start is a comment; each line after the first starts as one, and the last line ends
        String[] lines = end.split("\\\\n", -1);
        for (int i = 1; i < lines.length - 1; i++) {
            assertTrue("a notice line that isn't a comment: " + lines[i], lines[i].startsWith("// "));
        }
        assertEquals("the notice doesn't end its last line", "", lines[lines.length - 1]);
    }

    private static com.limelight.ui.OpenSourceNotices.Component notice(String name) {
        for (com.limelight.ui.OpenSourceNotices.Component c : com.limelight.ui.OpenSourceNotices.ALL) {
            if (c.name.equals(name)) {
                return c;
            }
        }
        throw new AssertionError("no notice named " + name);
    }
}
