package com.limelight.binding.video;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.system.Os;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The custom (Turnip) Vulkan driver for PyroWave on a real device: unpacking driver archives,
 * refusing files outside Starwire's driver folder (symlinks included), and falling back to the
 * system driver when the chosen one can't be loaded. The import checks themselves are covered on
 * the JVM by CustomVulkanDriverImportTest.
 *
 * These run in the app's own process, so they keep to folders of their own and never touch a
 * driver the user imported.
 */
@RunWith(AndroidJUnit4.class)
public class CustomVulkanDriverTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final File dir = new File(context.getFilesDir(), "custom_vulkan_driver_test");
    // Inside the real driver folder, for the test that goes through the native loader
    private final File nativeTestDir = new File(CustomVulkanDriver.directory(context), "test-broken-driver");

    @After
    public void cleanUp() {
        CustomVulkanDriver.removeAll(dir);
        dir.delete();
        CustomVulkanDriver.removeAll(nativeTestDir);
        nativeTestDir.delete();
        // Only goes if it was made just for this test and is empty again
        CustomVulkanDriver.directory(context).delete();
    }

    // Just enough of a 64-bit ARM shared library's ELF header for the checks, then the body
    private static byte[] driver(String body) {
        byte[] text = body.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[64 + text.length];
        bytes[0] = 0x7F;
        bytes[1] = 'E';
        bytes[2] = 'L';
        bytes[3] = 'F';
        bytes[4] = 2;
        bytes[5] = 1;
        bytes[6] = 1;
        bytes[16] = 3;
        bytes[18] = (byte) 183;
        System.arraycopy(text, 0, bytes, 64, text.length);
        return bytes;
    }

    private CustomVulkanDriver.Imported importZip(String name, Object[][] entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Object[] entry : entries) {
                zip.putNextEntry(new ZipEntry((String) entry[0]));
                zip.write(entry[1] instanceof byte[] ? (byte[]) entry[1]
                        : ((String) entry[1]).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return CustomVulkanDriver.importDriver(dir, name, new ByteArrayInputStream(out.toByteArray()));
    }

    private static byte[] read(String path) throws IOException {
        File file = new File(path);
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int total = 0;
            while (total < bytes.length) {
                total += in.read(bytes, total, bytes.length - total);
            }
        }
        return bytes;
    }

    @Test
    public void archiveUsesTheLibraryNamedInMetaJson() throws IOException {
        CustomVulkanDriver.Imported archive = importZip("turnip_test.adpkg", new Object[][] {
                {"meta.json", "{\"name\":\"Turnip\",\"libraryName\":\"vulkan.ad07XX.so\"}"},
                {"libother.so", driver("other")},
                {"vulkan.ad07XX.so", driver("driver")},
        });
        String path = CustomVulkanDriver.resolve(dir, archive.file.getAbsolutePath(), archive.sha256);
        assertEquals("vulkan.ad07XX.so", new File(path).getName());
        assertArrayEquals(driver("driver"), read(path));
    }

    @Test
    public void archiveWithoutMetaJsonPicksTheDriverLibrary() throws IOException {
        CustomVulkanDriver.Imported archive = importZip("turnip_nometa.zip", new Object[][] {
                {"README.txt", "hi"},
                {"libhelper.so", driver("helper")},
                {"libvulkan_freedreno.so", driver("freedreno")},
        });
        String path = CustomVulkanDriver.resolve(dir, archive.file.getAbsolutePath(), archive.sha256);
        assertEquals("libvulkan_freedreno.so", new File(path).getName());
    }

    @Test
    public void missingOrEmptySettingMeansTheSystemDriver() {
        assertNull(CustomVulkanDriver.resolve(context, null, null));
        assertNull(CustomVulkanDriver.resolve(context, "  ", null));
        assertNull(CustomVulkanDriver.resolve(context, "/data/nonexistent/driver.so", "00"));
    }

    // A link inside the folder to the system's own Vulkan loader, with that file's real hash,
    // is still outside the folder
    @Test
    public void aSymlinkOutOfTheDriverFolderIsRefused() throws Exception {
        File system = systemLibrary("libvulkan.so");
        if (system == null) {
            // Android 6 and older have no Vulkan loader (it came with Android 7), and this failed
            // there with FileNotFoundException. Their C library is just as much outside the folder.
            system = systemLibrary("libc.so");
        }
        assertNotNull("no system library to link to", system);
        assertTrue(dir.mkdirs() || dir.isDirectory());
        File link = new File(dir, "libvulkan_link.so");
        Os.symlink(system.getAbsolutePath(), link.getAbsolutePath());
        assertNull(CustomVulkanDriver.resolve(dir, link.getAbsolutePath(), CustomVulkanDriver.sha256(system)));
    }

    // The system's own copy of a library, 64-bit first, or null
    private static File systemLibrary(String name) {
        for (String dir : new String[] {"/system/lib64", "/system/lib"}) {
            File file = new File(dir, name);
            if (file.isFile()) {
                return file;
            }
        }
        return null;
    }

    // Removing the drivers deletes a symlink itself, never what it points to
    @Test
    public void removingDriversLeavesWhatASymlinkPointsTo() throws Exception {
        File elsewhere = new File(context.getFilesDir(), "custom_vulkan_driver_test_elsewhere");
        File kept = new File(elsewhere, "keep.txt");
        try {
            assertTrue(elsewhere.mkdirs() || elsewhere.isDirectory());
            try (FileOutputStream out = new FileOutputStream(kept)) {
                out.write("keep".getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(dir.mkdirs() || dir.isDirectory());
            File link = new File(dir, ".unpacked-link");
            Os.symlink(elsewhere.getAbsolutePath(), link.getAbsolutePath());

            CustomVulkanDriver.removeAll(dir);
            assertFalse(link.exists());
            assertTrue(kept.isFile());
        }
        finally {
            kept.delete();
            elsewhere.delete();
        }
    }

    @Test
    public void brokenDriverFallsBackToTheSystemDriver() throws IOException {
        // Not an ELF file at all. The import refuses these, so it's written straight into the
        // driver folder to check the native loader refuses it too.
        File fake = new File(nativeTestDir, "libvulkan_fake.so");
        assertTrue(nativeTestDir.mkdirs() || nativeTestDir.isDirectory());
        try (FileOutputStream out = new FileOutputStream(fake)) {
            out.write("not a real driver".getBytes(StandardCharsets.UTF_8));
        }
        String sha256 = CustomVulkanDriver.sha256(fake);
        assertEquals(fake.getCanonicalPath(), new File(CustomVulkanDriver.resolve(context,
                fake.getAbsolutePath(), sha256)).getCanonicalPath());

        boolean systemSupported = VulkanRendererBridge.isSupported();
        VulkanRendererBridge.configureCustomDriver(context, fake.getAbsolutePath(), sha256);
        try {
            // Probing PyroWave opens the custom driver; a broken one must not crash or stick
            VulkanRendererBridge.isPyrowaveSupported();
            assertFalse(VulkanRendererBridge.isCustomDriverActive());
            assertEquals(systemSupported, VulkanRendererBridge.isSupported());
        }
        finally {
            VulkanRendererBridge.configureCustomDriver(context, null, null);
        }
    }
}
