package com.limelight.ui;

import android.content.Context;

import com.limelight.BuildConfig;
import com.limelight.R;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything inside the Starwire APK that comes from another project, for Settings > About >
 * Open-source licences. Each licence text is in assets/licenses.
 *
 * Keep this up to date when a library, prebuilt or submodule changes: the versions and commits
 * come from app/build.gradle, the submodules and the prebuilts' SOURCE files. A copyright line is
 * only written here when it was read from the project's own licence file or source; otherwise it
 * names who makes it.
 */
public final class OpenSourceNotices {
    public static final String APACHE = "Apache-2.0.txt";

    public static final class Component {
        public final int section;
        public final String name;
        public final String version;
        public final String licence;
        public final String notice;
        public final String asset;
        public final String url;
        // The native libraries (.so files) it is built into
        final String[] libraries;

        Component(int section, String name, String version, String licence, String notice,
                  String asset, String url, String... libraries) {
            this.section = section;
            this.name = name;
            this.version = version;
            this.licence = licence;
            this.notice = notice;
            this.asset = asset;
            this.url = url;
            this.libraries = libraries;
        }

        /** "OkHttp 5.5.0" */
        public String title() {
            return version == null ? name : name + " " + version;
        }
    }

    private static final int APP = R.string.oss_section_app;
    private static final int JAVA = R.string.oss_section_java;
    private static final int NATIVE = R.string.oss_section_native;
    private static final int OTHER = R.string.oss_section_other;

    private static final String CORE = "libmoonlight-core.so";
    private static final String RENDERER = "libvulkan_renderer.so";
    private static final String PYROWAVE = "libpyrowave-shared.so";

    public static final Component[] ALL = {
            new Component(APP, "Starwire", BuildConfig.VERSION_NAME, "GPL-3.0",
                    "SicarioGaming", "GPL-3.0.txt",
                    "https://github.com/SicarioGaming/Starwire",
                    RENDERER, "libevdev_reader.so"),
            new Component(APP, "Moonlight Android", null, "GPL-3.0",
                    "Cameron Gutman and the Moonlight contributors, with the forks in Credits",
                    "GPL-3.0.txt", "https://github.com/moonlight-stream/moonlight-android",
                    RENDERER, "libevdev_reader.so"),
            new Component(APP, "moonlight-common-c", "976f261", "GPL-3.0",
                    "Cameron Gutman and the Moonlight contributors (Starwire's fork)",
                    "GPL-3.0.txt", "https://github.com/SicarioGaming/moonlight-common-c",
                    CORE),

            new Component(JAVA, "Bouncy Castle", "1.85.2", "MIT",
                    "Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc.",
                    "bouncycastle.txt", "https://www.bouncycastle.org"),
            new Component(JAVA, "OkHttp", "5.5.0", "Apache-2.0", "Square, Inc.",
                    APACHE, "https://github.com/lysine-dev/okhttp"),
            new Component(JAVA, "Okio", "3.18.1", "Apache-2.0", "Square, Inc.",
                    APACHE, "https://github.com/lysine-dev/okio"),
            new Component(JAVA, "Kotlin standard library", "2.2.21", "Apache-2.0", "JetBrains",
                    APACHE, "https://github.com/JetBrains/kotlin"),
            new Component(JAVA, "AndroidX Annotation, Startup and Tracing", null, "Apache-2.0",
                    "The Android Open Source Project",
                    APACHE, "https://cs.android.com/androidx/platform/frameworks/support"),
            new Component(JAVA, "JmDNS", "3.6.3", "Apache-2.0", "JmDNS",
                    APACHE, "https://github.com/jmdns/jmdns"),
            new Component(JAVA, "SLF4J", "2.0.7", "MIT", "Copyright (c) 2004-2023 QOS.ch",
                    "slf4j.txt", "https://www.slf4j.org"),
            new Component(JAVA, "JCodec", "0.2.5", "BSD-2-Clause", "Copyright 2008-2019 JCodecProject",
                    "jcodec.txt", "https://github.com/jcodec/jcodec"),
            new Component(JAVA, "ShieldControllerExtensions", "1.0.1", "MIT",
                    "Copyright (c) 2022 Cameron Gutman",
                    "shield-controller-extensions.txt", "https://github.com/cgutman/ShieldControllerExtensions"),
            new Component(JAVA, "HiddenApiBypass", "6.1", "Apache-2.0", "LSPosed",
                    APACHE, "https://github.com/LSPosed/AndroidHiddenApiBypass"),
            new Component(JAVA, "desugar_jdk_libs", "2.1.5", "GPL-2.0 with the Classpath Exception",
                    "Google, from OpenJDK", "desugar_jdk_libs.txt",
                    "https://github.com/google/desugar_jdk_libs"),

            new Component(NATIVE, "ENet", "aca8784", "MIT", "Copyright (c) 2002-2020 Lee Salzman",
                    "enet.txt", "https://github.com/cgutman/enet", CORE),
            new Component(NATIVE, "nanors", "b1e3c22", "MIT", "Copyright (c) 2021 Joseph Calderon",
                    "nanors.txt", "https://github.com/sleepybishop/nanors", CORE),
            new Component(NATIVE, "Opus", "1.6.1", "BSD-3-Clause",
                    "Copyright 2001-2023 Xiph.Org, Skype Limited, Octasic and others",
                    "opus.txt", "https://github.com/xiph/opus", CORE),
            new Component(NATIVE, "OpenSSL (libcrypto)", "4.0.2", "Apache-2.0",
                    "The OpenSSL Project Authors", APACHE, "https://github.com/openssl/openssl", CORE),
            new Component(NATIVE, "SDL controller code", null, "Zlib",
                    "Copyright (C) 1997-2026 Sam Lantinga", "sdl.txt",
                    "https://github.com/libsdl-org/SDL", CORE),
            new Component(NATIVE, "Valve's controller list", null, "Zlib", "Copyright (C) Valve Corporation",
                    "valve-controller-list.txt", "https://github.com/libsdl-org/SDL", CORE),
            new Component(NATIVE, "Android NDK cpufeatures", null, "BSD-2-Clause",
                    "Copyright (C) 2010 The Android Open Source Project", "android-cpufeatures.txt",
                    "https://android.googlesource.com/platform/ndk/+/refs/heads/main/sources/android/cpufeatures",
                    CORE),
            new Component(NATIVE, "libadrenotools and linkernsbypass", "8fae8ce", "BSD-2-Clause",
                    "Copyright (c) 2021, Billy Laws", "libadrenotools.txt",
                    "https://github.com/bylaws/libadrenotools",
                    "libadrenotools.so", "libhook_impl.so", "libmain_hook.so",
                    "libfile_redirect_hook.so", "libgsl_alloc_hook.so"),
            new Component(NATIVE, "PyroWave", "89f7e47", "MIT", "Copyright (c) 2025 Hans-Kristian Arntzen",
                    "pyrowave.txt", "https://github.com/Themaister/pyrowave", PYROWAVE),
            new Component(NATIVE, "Granite", "1b2d180", "MIT", "Copyright (c) 2017-2026 Hans-Kristian Arntzen",
                    "granite.txt", "https://github.com/Themaister/Granite", PYROWAVE),
            new Component(NATIVE, "volk", "47cddf7", "MIT", "Copyright (c) 2018-2026 Arseny Kapoulkine",
                    "volk.txt", "https://github.com/zeux/volk", PYROWAVE),
            new Component(NATIVE, "Vulkan-Headers", "6802bb4", "Apache-2.0 or MIT",
                    "Copyright 2015-2026 The Khronos Group Inc.", "vulkan-headers.txt",
                    "https://github.com/KhronosGroup/Vulkan-Headers", PYROWAVE),
            new Component(NATIVE, "LLVM libc++", null, "Apache-2.0 with LLVM exceptions",
                    "The LLVM Project", "llvm-libcxx.txt", "https://github.com/llvm/llvm-project",
                    CORE, RENDERER, PYROWAVE),

            new Component(OTHER, "Material Design icons", null, "Apache-2.0", "Google",
                    APACHE, "https://github.com/google/material-design-icons"),
            new Component(OTHER, "Public Suffix List", null, "MPL-2.0",
                    "publicsuffix.org, carried by OkHttp", "public-suffix-list.txt",
                    "https://publicsuffix.org"),
    };

    private OpenSourceNotices() {}

    /** A component's licence text from assets/licenses. */
    public static String readLicence(Context context, Component component) throws IOException {
        try (InputStream in = context.getAssets().open("licenses/" + component.asset)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, read);
            }
            // A Windows checkout can give the files CRLF line endings
            return bytes.toString("UTF-8").replace("\r", "");
        }
    }

    /** The section headings, in order. */
    public static int[] sections() {
        return new int[] {APP, JAVA, NATIVE, OTHER};
    }

    /** The components built into a native library, by its file name (empty if none are listed). */
    public static List<Component> forLibrary(String fileName) {
        List<Component> found = new ArrayList<>();
        for (Component component : ALL) {
            for (String library : component.libraries) {
                if (library.equals(fileName)) {
                    found.add(component);
                }
            }
        }
        return found;
    }
}
