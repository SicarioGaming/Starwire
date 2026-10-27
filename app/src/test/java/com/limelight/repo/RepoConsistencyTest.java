package com.limelight.repo;

import static org.junit.Assert.*;

import com.limelight.RepoFiles;
import com.limelight.ui.AboutCredits;
import com.limelight.ui.OpenSourceNotices;
import com.limelight.ui.SettingsImpact;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic checks of the code against the repository's other files. No device needed. */
public class RepoConsistencyTest {
    @Test
    public void creditsMatchTheReadme() throws Exception {
        String readme = RepoFiles.read("README.md");
        List<String> missing = new ArrayList<>();
        for (AboutCredits.Credit c : AboutCredits.ALL) {
            if (!readme.contains("[" + c.project + "](" + c.url + ")")) {
                missing.add(c.project + " " + c.url);
            }
        }
        assertTrue("in the app's credits but not the README table: " + missing, missing.isEmpty());
    }

    // The other way round: a project added to the README's Credits table is on the app's Credits
    // page too (AboutCredits says to change both together, and only the check above was here)
    @Test
    public void readmeCreditsAreInTheApp() throws Exception {
        Set<String> app = new HashSet<>();
        for (AboutCredits.Credit c : AboutCredits.ALL) {
            app.add("[" + c.project + "](" + c.url + ")");
        }
        Pattern link = Pattern.compile("\\[[^\\]]+\\]\\([^)]+\\)");
        List<String> missing = new ArrayList<>();
        int rows = 0;
        boolean inCredits = false;
        for (String line : RepoFiles.read("README.md").split("\n")) {
            line = line.trim();
            if (line.startsWith("## ")) {
                inCredits = line.equals("## Credits");
                continue;
            }
            if (!inCredits || !line.startsWith("| [")) {
                continue;
            }
            rows++;
            // The Project column, which can link more than one (Apollo / Sunshine)
            Matcher m = link.matcher(line.substring(0, line.indexOf(" | ")));
            while (m.find()) {
                if (!app.contains(m.group())) {
                    missing.add(m.group());
                }
            }
        }
        assertTrue("no rows found in the README's Credits table", rows > 0);
        assertTrue("in the README's Credits table but not the app's: " + missing, missing.isEmpty());
    }

    @Test
    public void everyImpactKeyIsARealSetting() throws Exception {
        String prefs = RepoFiles.read("app/src/main/res/xml/preferences.xml");
        Field f = SettingsImpact.class.getDeclaredField("IMPACT");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Integer> impact = (Map<String, Integer>) f.get(null);
        List<String> unknown = new ArrayList<>();
        for (String key : impact.keySet()) {
            if (!prefs.contains("android:key=\"" + key + "\"")) {
                unknown.add(key);
            }
        }
        assertTrue("Impact lines for settings that don't exist: " + unknown, unknown.isEmpty());
    }

    @Test
    public void noticeVersionsMatchTheBuild() throws Exception {
        String gradle = RepoFiles.read("app/build.gradle");
        String[][] deps = {
                {"Bouncy Castle", "org.bouncycastle:bcprov-jdk18on:"},
                {"OkHttp", "com.squareup.okhttp3:okhttp:"},
                {"JmDNS", "org.jmdns:jmdns:"},
                {"JCodec", "org.jcodec:jcodec:"},
                {"ShieldControllerExtensions", "com.github.cgutman:ShieldControllerExtensions:"},
                {"desugar_jdk_libs", "com.android.tools:desugar_jdk_libs:"},
                {"HiddenApiBypass", "org.lsposed.hiddenapibypass:hiddenapibypass:"},
        };
        List<String> drift = new ArrayList<>();
        for (String[] d : deps) {
            Matcher m = Pattern.compile(Pattern.quote(d[1]) + "([^'\"]+)").matcher(gradle);
            assertTrue(d[1] + " not in build.gradle", m.find());
            String version = m.group(1);
            String notice = noticeVersion(d[0]);
            if (!version.equals(notice)) {
                drift.add(d[0] + ": build " + version + ", notice " + notice);
            }
        }
        assertTrue("licence page versions out of date: " + drift, drift.isEmpty());
    }

    // HiddenApiBypass reaches hidden Android calls, so Gradle checks its files' SHA-256 before
    // every build. The checked version has to be the one the app uses, or the check is for nothing.
    @Test
    public void hiddenApiBypassIsTheVersionGradleChecks() throws Exception {
        String gradle = RepoFiles.read("app/build.gradle");
        Matcher used = Pattern.compile("'org\\.lsposed\\.hiddenapibypass:hiddenapibypass:([^']+)'").matcher(gradle);
        assertTrue(used.find());
        String version = used.group(1);
        String metadata = RepoFiles.read("gradle/verification-metadata.xml");
        assertTrue("verification-metadata.xml doesn't check hiddenapibypass " + version, metadata.contains(
                "<component group=\"org.lsposed.hiddenapibypass\" name=\"hiddenapibypass\" version=\"" + version + "\">"));
        Matcher aar = Pattern.compile("<artifact name=\"hiddenapibypass-" + Pattern.quote(version)
                + "\\.aar\">\\s*<sha256 value=\"([0-9a-f]{64})\"").matcher(metadata);
        assertTrue("no SHA-256 for the AAR", aar.find());
        // Everything else is trusted for now; this library must not be
        assertTrue(metadata.contains("<trust group=\"^(?!org[.]lsposed[.]hiddenapibypass$).*$\" regex=\"true\""));
        assertTrue("PROVENANCE.md records it", RepoFiles.read("docs/PROVENANCE.md").contains(aar.group(1)));
    }

    @Test
    public void prebuiltNoticesMatchTheirSourceFiles() throws Exception {
        String pyro = RepoFiles.read("app/src/main/jni/pyrowave/SOURCE").trim();
        String adreno = RepoFiles.read("app/src/main/jni/adrenotools/SOURCE").trim();
        assertTrue(pyro.startsWith(noticeVersion("PyroWave")));
        assertTrue(adreno.contains("libadrenotools " + noticeVersion("libadrenotools and linkernsbypass")));
    }

    @Test
    public void everyPrebuiltLibraryHasANotice() throws Exception {
        Set<String> covered = new HashSet<>();
        for (OpenSourceNotices.Component c : OpenSourceNotices.ALL) {
            Field f = OpenSourceNotices.Component.class.getDeclaredField("libraries");
            f.setAccessible(true);
            for (String lib : (String[]) f.get(c)) covered.add(lib);
        }
        Set<String> missing = new TreeSet<>();
        for (String dir : new String[] {"app/src/main/jni/pyrowave/lib", "app/src/main/jni/adrenotools/lib"}) {
            for (File abi : RepoFiles.file(dir).listFiles()) {
                for (File so : abi.listFiles()) {
                    if (!covered.contains(so.getName())) missing.add(so.getName());
                }
            }
        }
        assertTrue("prebuilt .so files without a licence notice: " + missing, missing.isEmpty());
    }

    // Every translation keeps the placeholders of the English string (the 12.2 rebase broke ja/ta)
    @Test
    public void translationsKeepTheirPlaceholders() throws Exception {
        Map<String, String> english = strings(RepoFiles.file("app/src/main/res/values/strings.xml"));
        List<String> broken = new ArrayList<>();
        for (File dir : RepoFiles.file("app/src/main/res").listFiles()) {
            File f = new File(dir, "strings.xml");
            if (!dir.getName().startsWith("values-") || !f.isFile()) continue;
            for (Map.Entry<String, String> e : strings(f).entrySet()) {
                String en = english.get(e.getKey());
                if (en != null && !placeholders(en).equals(placeholders(e.getValue()))) {
                    broken.add(dir.getName() + "/" + e.getKey() + " " + placeholders(en) + " vs " + placeholders(e.getValue()));
                }
            }
        }
        assertTrue(broken.size() + " translations with different placeholders: " + broken, broken.isEmpty());
    }

    // The prebuilt static libraries linked into libmoonlight-core.so, by the folder they're in
    @Test
    public void everyPrebuiltStaticLibraryHasANotice() throws Exception {
        Map<String, String> noticeFor = new java.util.HashMap<>();
        noticeFor.put("openssl", "OpenSSL (libcrypto)");
        noticeFor.put("opus", "Opus");
        List<String> problems = new ArrayList<>();
        int found = 0;
        File core = RepoFiles.file("app/src/main/jni/moonlight-core");
        for (File dir : core.listFiles()) {
            if (!dir.isDirectory() || dir.getName().equals("moonlight-common-c")) continue;
            for (File abi : dir.listFiles()) {
                File[] libs = abi.listFiles((d, name) -> name.endsWith(".a"));
                if (libs == null) continue;
                for (File lib : libs) {
                    found++;
                    String notice = noticeFor.get(dir.getName());
                    if (notice == null) {
                        problems.add(dir.getName() + "/" + abi.getName() + "/" + lib.getName() + " has no notice");
                    } else if (!libraries(componentNamed(notice)).contains("libmoonlight-core.so")) {
                        problems.add(notice + " doesn't list libmoonlight-core.so");
                    }
                }
            }
        }
        assertEquals("prebuilt .a files", 8, found);
        assertTrue(problems.toString(), problems.isEmpty());
    }

    @Test
    public void openSslNoticeMatchesTheHeaders() throws Exception {
        String header = RepoFiles.read("app/src/main/jni/moonlight-core/openssl/include/openssl/opensslv.h");
        Matcher m = Pattern.compile("define OPENSSL_VERSION_STR \"([^\"]+)\"").matcher(header);
        assertTrue(m.find());
        assertEquals(m.group(1), noticeVersion("OpenSSL (libcrypto)"));
    }

    // Settings > About > Open-source licences opens each of these; the device test checks the APK
    @Test
    public void everyNoticeHasItsLicenceText() throws Exception {
        List<String> problems = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (OpenSourceNotices.Component c : OpenSourceNotices.ALL) {
            used.add(c.asset);
            File f = RepoFiles.file("app/src/main/assets/licenses/" + c.asset);
            if (!f.isFile()) {
                problems.add(c.name + ": no " + c.asset);
                continue;
            }
            String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("\r", "");
            if (text.trim().length() < 100) problems.add(c.name + ": " + c.asset + " is nearly empty");
            if (!c.url.startsWith("https://")) problems.add(c.name + ": no https project page");
            if (c.notice.startsWith("Copyright") && !text.contains(c.notice.replace(" and others", ""))) {
                problems.add(c.name + ": \"" + c.notice + "\" isn't in " + c.asset);
            }
        }
        for (File f : RepoFiles.file("app/src/main/assets/licenses").listFiles()) {
            if (!used.contains(f.getName())) problems.add(f.getName() + " isn't used by any notice");
        }
        assertTrue(problems.toString(), problems.isEmpty());
    }

    // Each option a game can set has its global setting and its per-game one, and each per-game
    // option on the App Settings screen is one the stream applies
    @Test
    public void gameOptionsHaveBothSettings() throws Exception {
        String global = RepoFiles.read("app/src/main/res/xml/preferences.xml");
        String perGame = RepoFiles.read("app/src/main/res/xml/app_preferences.xml");
        Field f = com.limelight.preferences.PreferenceConfiguration.class.getDeclaredField("GAME_OPTION_KEYS");
        f.setAccessible(true);
        Set<String> keys = new HashSet<>(java.util.Arrays.asList((String[]) f.get(null)));
        List<String> problems = new ArrayList<>();
        for (String key : keys) {
            if (!global.contains("android:key=\"" + key + "\"")) problems.add(key + " isn't in preferences.xml");
            if (!perGame.contains("android:key=\"game_" + key + "\"")) problems.add("game_" + key + " isn't in app_preferences.xml");
        }
        Matcher m = Pattern.compile("android:key=\"game_(\\w+)\"").matcher(perGame);
        while (m.find()) {
            if (!keys.contains(m.group(1))) problems.add("game_" + m.group(1) + " isn't in GAME_OPTION_KEYS");
        }
        assertEquals("per-game options", 10, keys.size());
        assertTrue(problems.toString(), problems.isEmpty());
    }

    // The per-game screen's own keys for the main settings point at settings that have a line
    @Test
    public void impactAliasesArePerGameSettings() throws Exception {
        String perGame = RepoFiles.read("app/src/main/res/xml/app_preferences.xml");
        Field aliasesField = SettingsImpact.class.getDeclaredField("ALIASES");
        aliasesField.setAccessible(true);
        Field impactField = SettingsImpact.class.getDeclaredField("IMPACT");
        impactField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> aliases = (Map<String, String>) aliasesField.get(null);
        @SuppressWarnings("unchecked")
        Map<String, Integer> impact = (Map<String, Integer>) impactField.get(null);
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            if (!perGame.contains("android:key=\"" + alias.getKey() + "\"")) problems.add(alias.getKey() + " isn't in app_preferences.xml");
            if (!impact.containsKey(alias.getValue())) problems.add(alias.getKey() + " points at " + alias.getValue() + ", which has no line");
        }
        assertTrue(problems.toString(), problems.isEmpty());
    }

    private static String noticeVersion(String name) {
        return componentNamed(name).version;
    }

    private static OpenSourceNotices.Component componentNamed(String name) {
        for (OpenSourceNotices.Component c : OpenSourceNotices.ALL) {
            if (c.name.equals(name)) return c;
        }
        throw new AssertionError("no notice named " + name);
    }

    private static List<String> libraries(OpenSourceNotices.Component c) throws Exception {
        Field f = OpenSourceNotices.Component.class.getDeclaredField("libraries");
        f.setAccessible(true);
        return java.util.Arrays.asList((String[]) f.get(c));
    }

    private static final Pattern STRING = Pattern.compile("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", Pattern.DOTALL);
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[sdfxXc%]");

    private static Map<String, String> strings(File f) throws Exception {
        String xml = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        Map<String, String> out = new java.util.HashMap<>();
        Matcher m = STRING.matcher(xml);
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    private static List<String> placeholders(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(s);
        while (m.find()) if (!m.group().equals("%%")) out.add(m.group());
        java.util.Collections.sort(out);
        return out;
    }
}
