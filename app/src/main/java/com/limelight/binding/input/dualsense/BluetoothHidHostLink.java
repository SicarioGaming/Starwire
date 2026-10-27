package com.limelight.binding.input.dualsense;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import com.limelight.LimeLog;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Android's Bluetooth HID host (the service that runs a paired DualSense) and its hidden report
 * calls. Starwire asks for the service through the public getProfileProxy(context, listener, 4),
 * then finds sendData and setReport, which Android hides from apps, with HiddenApiBypass by
 * LSPosed (Apache-2.0). On the maintainer's Android 16 tablet, with only the Nearby devices
 * permission, HiddenApiBypass reached them where plain reflection was refused (a test app and a
 * made-up address, no pad). The same route is what Moonlight V+ by qiin2333 uses
 * (AndroidBluetoothHidHostTransport.kt at 5840bab3); the four call forms come from there.
 *
 * Hidden calls can change with any Android or Bluetooth update, which is why DualSenseSession
 * checks the link on every connect and falls back to today's path. Android 12 and newer only.
 */
public final class BluetoothHidHostLink implements HidHostLink {
    /** BluetoothProfile.HID_HOST, which the public API doesn't name. */
    static final int HID_HOST_PROFILE = 4;

    private final Context context;
    private final BluetoothAdapter adapter;
    private final Map<SendForm, Method> methods = new EnumMap<>(SendForm.class);
    private volatile BluetoothProfile proxy;
    private volatile boolean closed;

    public BluetoothHidHostLink(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager manager = (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = manager != null ? manager.getAdapter() : null;
    }

    /** Nearby devices (BLUETOOTH_CONNECT) is allowed. */
    public static boolean hasPermission(Context context) {
        return Build.VERSION.SDK_INT >= DualSenseChecks.MIN_SDK
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    @SuppressLint("MissingPermission") // isEnabled needs no runtime permission
    public boolean bluetoothOn() {
        try {
            return adapter != null && adapter.isEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Asks for the service. The listener hears back on the main thread. False if Android said no
     * at once; the listener isn't called then.
     */
    @Override
    public boolean open(Listener listener) {
        if (adapter == null) {
            return false;
        }
        try {
            return adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile connected) {
                    if (profile != HID_HOST_PROFILE) {
                        return;
                    }
                    if (closed) {
                        adapter.closeProfileProxy(HID_HOST_PROFILE, connected);
                        return;
                    }
                    findMethods(connected.getClass());
                    proxy = connected;
                    listener.onLinkReady();
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    if (profile == HID_HOST_PROFILE) {
                        proxy = null;
                        LimeLog.warning("DualSense: the Bluetooth HID service went away");
                    }
                }
            }, HID_HOST_PROFILE);
        } catch (RuntimeException e) {
            LimeLog.warning("DualSense: getProfileProxy failed: " + e);
            return false;
        }
    }

    /** True once the service answered and hasn't gone away. */
    public boolean isOpen() {
        return proxy != null;
    }

    @Override
    public void close() {
        closed = true;
        BluetoothProfile p = proxy;
        proxy = null;
        if (p != null && adapter != null) {
            try {
                adapter.closeProfileProxy(HID_HOST_PROFILE, p);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    @SuppressLint("MissingPermission") // DualSenseChecks checked BLUETOOTH_CONNECT before any of this
    public List<Device> connectedDevices() {
        BluetoothProfile p = proxy;
        if (p == null || !hasPermission(context)) {
            return Collections.emptyList();
        }
        List<Device> devices = new ArrayList<>();
        try {
            for (BluetoothDevice device : p.getConnectedDevices()) {
                devices.add(new Device(device.getAddress(), device.getName()));
            }
        } catch (RuntimeException e) {
            LimeLog.warning("DualSense: couldn't list the HID devices: " + e);
        }
        return devices;
    }

    @Override
    public Set<SendForm> forms() {
        synchronized (methods) {
            return methods.isEmpty() ? EnumSet.noneOf(SendForm.class) : EnumSet.copyOf(methods.keySet());
        }
    }

    @Override
    @SuppressLint("MissingPermission")
    public boolean call(SendForm form, String address, byte[] report) throws Exception {
        BluetoothProfile p = proxy;
        Method method;
        synchronized (methods) {
            method = methods.get(form);
        }
        if (p == null || method == null || address == null || !hasPermission(context)) {
            return false;
        }
        BluetoothDevice device = adapter.getRemoteDevice(address);
        return Boolean.TRUE.equals(method.invoke(p, arguments(form, method, device, report)));
    }

    // The arguments for a form's method: the device, then for setReport the output report type
    // in the method's own type (byte or int), then the report as the form takes it
    static Object[] arguments(SendForm form, Method method, Object device, byte[] report) {
        return form.setReport
                ? new Object[] {device, reportType(method), form.payload(report)}
                : new Object[] {device, form.payload(report)};
    }

    // Each form by name and parameters, on the proxy's class and android.bluetooth.BluetoothHidHost,
    // and their parents, as V+'s resolveMethods does (AndroidBluetoothHidHostTransport.kt:184-233).
    // setReport's report type may be a byte or an int.
    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"}) // the hidden HID calls are the point
    private void findMethods(Class<?> proxyClass) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // HiddenApiBypass needs Android 9; DualSenseChecks already stopped anything older than 12
            return;
        }
        List<Class<?>> classes = new ArrayList<>();
        classes.add(proxyClass);
        try {
            Class<?> hid = Class.forName("android.bluetooth.BluetoothHidHost");
            if (hid != proxyClass) {
                classes.add(hid);
            }
        } catch (Throwable ignored) {
        }

        Map<SendForm, Method> found = new EnumMap<>(SendForm.class);
        for (Class<?> start : classes) {
            for (Class<?> c = start; c != null; c = c.getSuperclass()) {
                List<?> declared;
                try {
                    declared = HiddenApiBypass.getDeclaredMethods(c);
                } catch (Throwable t) {
                    LimeLog.warning("DualSense: couldn't list " + c.getName() + ": " + t);
                    continue;
                }
                for (Object o : declared) {
                    if (!(o instanceof Method)) {
                        continue;
                    }
                    Method m = (Method) o;
                    SendForm form = formOf(m);
                    if (form != null && !found.containsKey(form)) {
                        try {
                            m.setAccessible(true);
                        } catch (RuntimeException ignored) {
                        }
                        found.put(form, m);
                    }
                }
            }
        }
        synchronized (methods) {
            methods.clear();
            methods.putAll(found);
        }
        LimeLog.info("DualSense: call forms on this device: " + found.keySet());
    }

    static SendForm formOf(Method m) {
        Class<?>[] p = m.getParameterTypes();
        if (p.length < 2 || !BluetoothDevice.class.isAssignableFrom(p[0])) {
            return null;
        }
        Class<?> payload = p[p.length - 1];
        if (!payload.equals(byte[].class) && !payload.equals(String.class)) {
            return null;
        }
        boolean bytes = payload.equals(byte[].class);
        if (m.getName().equals("sendData") && p.length == 2) {
            return bytes ? SendForm.SEND_DATA_BYTES : SendForm.SEND_DATA_HEX;
        }
        if (m.getName().equals("setReport") && p.length == 3 && isReportType(p[1])) {
            return bytes ? SendForm.SET_REPORT_BYTES : SendForm.SET_REPORT_HEX;
        }
        return null;
    }

    private static boolean isReportType(Class<?> type) {
        return type == byte.class || type == Byte.class || type == int.class || type == Integer.class;
    }

    // Not a ?: expression: one with a Byte and an Integer is an int, so a byte method got an Integer
    static Object reportType(Method m) {
        Class<?> type = m.getParameterTypes()[1];
        if (type == byte.class || type == Byte.class) {
            return Byte.valueOf((byte) SendForm.REPORT_TYPE_OUTPUT);
        }
        return Integer.valueOf(SendForm.REPORT_TYPE_OUTPUT);
    }
}
