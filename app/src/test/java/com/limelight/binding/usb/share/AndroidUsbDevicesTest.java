package com.limelight.binding.usb.share;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Looper;
import android.os.Parcelable;

import com.limelight.binding.input.driver.UsbDriverService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowUsbManager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * The real UsbDevices: what it reads from Android's UsbDevice, and the broadcasts it listens to,
 * on Robolectric's UsbManager. Android's permission dialog itself needs a device.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class AndroidUsbDevicesTest {
    private final Context context = RuntimeEnvironment.getApplication();
    private final ShadowUsbManager usb = shadowOf((UsbManager) context.getSystemService(Context.USB_SERVICE));
    private final List<String> events = new ArrayList<>();
    private int promptsShown;
    private int promptsClosed;
    private AndroidUsbDevices devices;

    // Android's own classes, made the way its USB service makes them (their constructors are hidden)
    static UsbDevice pad(String name, String product) throws Exception {
        UsbEndpoint in = UsbEndpoint.class.getConstructor(int.class, int.class, int.class, int.class)
                .newInstance(0x81, 3, 64, 4);
        UsbEndpoint out = UsbEndpoint.class.getConstructor(int.class, int.class, int.class, int.class)
                .newInstance(0x02, 3, 64, 4);
        UsbInterface hid = UsbInterface.class.getConstructor(int.class, int.class, String.class, int.class,
                int.class, int.class).newInstance(0, 0, null, 0x03, 0, 0);
        UsbInterface.class.getMethod("setEndpoints", Parcelable[].class).invoke(hid, (Object) new Parcelable[] {in, out});
        UsbConfiguration config = UsbConfiguration.class.getConstructor(int.class, String.class, int.class, int.class)
                .newInstance(1, null, 0x80, 250);
        UsbConfiguration.class.getMethod("setInterfaces", Parcelable[].class).invoke(config, (Object) new Parcelable[] {hid});
        Class<?> builder = Class.forName("android.hardware.usb.UsbDevice$Builder");
        Object b = builder.getConstructor(String.class, int.class, int.class, int.class, int.class, int.class,
                String.class, String.class, String.class, UsbConfiguration[].class, String.class, boolean.class,
                boolean.class, boolean.class, boolean.class, boolean.class)
                .newInstance(name, 0x054C, 0x0CE6, 0, 0, 0, "Sony", product, "1.00",
                        new UsbConfiguration[] {config}, null, false, false, false, false, false);
        // UsbDevice wants something to read the serial number through; nothing here reads it
        Class<?> reader = Class.forName("android.hardware.usb.IUsbSerialReader");
        Object noSerial = Proxy.newProxyInstance(reader.getClassLoader(), new Class<?>[] {reader},
                (proxy, method, args) -> null);
        return (UsbDevice) builder.getMethod("build", reader).invoke(b, noSerial);
    }

    @Before
    public void setUp() {
        devices = new AndroidUsbDevices(context, new UsbDriverService.UsbDriverStateListener() {
            @Override
            public void onUsbPermissionPromptStarting() {
                promptsShown++;
            }

            @Override
            public void onUsbPermissionPromptCompleted() {
                promptsClosed++;
            }
        });
    }

    private void broadcast(String action, UsbDevice device, boolean granted) {
        Intent intent = new Intent(action).setPackage(context.getPackageName());
        intent.putExtra(UsbManager.EXTRA_DEVICE, device);
        intent.putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, granted);
        context.sendBroadcast(intent);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void listen() {
        devices.start(new UsbDevices.Listener() {
            @Override
            public void onAttached(UsbDeviceSummary device) {
                events.add("attached " + device.key);
            }

            @Override
            public void onDetached(String key) {
                events.add("detached " + key);
            }
        });
    }

    @Test
    public void readsWhatAndroidShowsOfADevice() throws Exception {
        UsbDeviceSummary d = AndroidUsbDevices.summarize(pad("/dev/bus/usb/001/002", "DualSense‮ Wireless Controller"));
        assertEquals("/dev/bus/usb/001/002", d.key);
        assertEquals("DualSense Wireless Controller", d.name);
        assertEquals(0x054C, d.vendorId);
        assertEquals(0x0CE6, d.productId);
        assertEquals(1, d.configurationCount);
        assertEquals(1, d.interfaces.size());
        UsbDeviceSummary.Interface hid = d.interfaces.get(0);
        assertEquals(0x03, hid.interfaceClass);
        assertEquals(2, hid.endpoints.size());
        assertArrayEquals(new int[] {0x81, 3}, hid.endpoints.get(0));
        assertArrayEquals(new int[] {0x02, 3}, hid.endpoints.get(1));
        assertEquals(UsbVerdict.ALLOW_UNCHECKED, UsbSharePreview.judge(d));
    }

    @Test
    public void listsThePluggedInDevicesInOrder() throws Exception {
        usb.addOrUpdateUsbDevice(pad("/dev/bus/usb/001/005", "B"), false);
        usb.addOrUpdateUsbDevice(pad("/dev/bus/usb/001/002", "A"), false);
        List<UsbDeviceSummary> list = devices.list();
        assertEquals(2, list.size());
        assertEquals("/dev/bus/usb/001/002", list.get(0).key);
        assertEquals("/dev/bus/usb/001/005", list.get(1).key);
        assertNull(devices.device("/dev/bus/usb/009/009"));
    }

    @Test
    public void hearsDevicesComeAndGo() throws Exception {
        listen();
        UsbDevice pad = pad("/dev/bus/usb/001/002", "Pad");
        broadcast(UsbManager.ACTION_USB_DEVICE_ATTACHED, pad, false);
        broadcast(UsbManager.ACTION_USB_DEVICE_DETACHED, pad, false);
        assertEquals(List.of("attached /dev/bus/usb/001/002", "detached /dev/bus/usb/001/002"), events);
        devices.stop();
        broadcast(UsbManager.ACTION_USB_DEVICE_ATTACHED, pad, false);
        assertEquals(2, events.size());
    }

    @Test
    public void aDeviceWithPermissionIsAnsweredAtOnce() throws Exception {
        usb.addOrUpdateUsbDevice(pad("/dev/bus/usb/001/002", "Pad"), true);
        int[] answer = {-1};
        devices.requestPermission("/dev/bus/usb/001/002", result -> answer[0] = result);
        assertEquals(UsbDevices.GRANTED, answer[0]);
        assertEquals(0, promptsShown);
        // One that's gone is a no
        devices.requestPermission("/dev/bus/usb/009/009", result -> answer[0] = result);
        assertEquals(UsbDevices.DENIED, answer[0]);
    }

    // Before Android 13 another app can send this broadcast too, so its extra counts for nothing:
    // only UsbManager saying Starwire has permission does
    @Test
    public void onlyAndroidsOwnAnswerCounts() throws Exception {
        listen();
        UsbDevice pad = pad("/dev/bus/usb/001/002", "Pad");
        usb.addOrUpdateUsbDevice(pad, false);
        List<Integer> answers = new ArrayList<>();
        devices.requestPermission("/dev/bus/usb/001/002", answers::add);
        assertEquals(1, promptsShown);
        assertTrue(answers.isEmpty());

        broadcast(AndroidUsbDevices.ACTION_PERMISSION, pad, true);
        assertEquals(List.of(UsbDevices.DENIED), answers);
        assertEquals(1, promptsClosed);

        // Asked again, and this time Android grants it
        devices.requestPermission("/dev/bus/usb/001/002", answers::add);
        usb.addOrUpdateUsbDevice(pad, true);
        broadcast(AndroidUsbDevices.ACTION_PERMISSION, pad, true);
        assertEquals(List.of(UsbDevices.DENIED, UsbDevices.GRANTED), answers);
        // An answer for nothing that's waiting changes nothing
        broadcast(AndroidUsbDevices.ACTION_PERMISSION, pad, true);
        assertEquals(2, answers.size());
        assertEquals(2, promptsClosed);
    }

    @Test
    public void unpluggedWhileAskingIsANo() throws Exception {
        listen();
        UsbDevice pad = pad("/dev/bus/usb/001/002", "Pad");
        usb.addOrUpdateUsbDevice(pad, false);
        List<Integer> answers = new ArrayList<>();
        devices.requestPermission("/dev/bus/usb/001/002", answers::add);
        usb.removeUsbDevice(pad);
        broadcast(UsbManager.ACTION_USB_DEVICE_DETACHED, pad, false);
        assertEquals(List.of(UsbDevices.DENIED), answers);
        // Told as unplugged first, so the share flow drops the question with the device
        assertEquals(List.of("detached /dev/bus/usb/001/002"), events);
    }
}
