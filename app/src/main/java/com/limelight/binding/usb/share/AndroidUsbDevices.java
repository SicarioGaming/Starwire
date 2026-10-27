package com.limelight.binding.usb.share;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import com.limelight.LimeLog;
import com.limelight.binding.input.driver.UsbDriverService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** UsbDevices from Android's UsbManager and its broadcasts. */
public final class AndroidUsbDevices implements UsbDevices {
    // Not the USB driver's permission action, so neither one takes the other's answers
    static final String ACTION_PERMISSION = "com.limelight.USB_SHARE_PERMISSION";

    private final Context context;
    private final UsbManager manager;
    private final UsbDriverService.UsbDriverStateListener prompts;
    private Listener listener;
    private String pendingKey;
    private PermissionCallback pendingCallback;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device == null || intent.getAction() == null) {
                return;
            }
            switch (intent.getAction()) {
                case UsbManager.ACTION_USB_DEVICE_ATTACHED:
                    if (listener != null) {
                        listener.onAttached(summarize(device));
                    }
                    break;
                case UsbManager.ACTION_USB_DEVICE_DETACHED:
                    if (listener != null) {
                        listener.onDetached(device.getDeviceName());
                    }
                    if (device.getDeviceName().equals(pendingKey)) {
                        answer(DENIED);
                    }
                    break;
                case ACTION_PERMISSION:
                    if (device.getDeviceName().equals(pendingKey)) {
                        // Before Android 13 this receiver is open to other apps, and the extra
                        // can say anything, so it's Android's own answer that counts
                        answer(manager.hasPermission(device) ? GRANTED : DENIED);
                    }
                    break;
            }
        }
    };

    /** prompts hears when Android's permission dialog shows and closes, as the USB driver's do */
    public AndroidUsbDevices(Context context, UsbDriverService.UsbDriverStateListener prompts) {
        this.context = context;
        this.manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.prompts = prompts;
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void start(Listener listener) {
        this.listener = listener;
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        }
        else {
            context.registerReceiver(receiver, filter);
        }
    }

    @Override
    public void stop() {
        listener = null;
        answer(DENIED);
        try {
            context.unregisterReceiver(receiver);
        }
        catch (IllegalArgumentException e) {
            // Never started
        }
    }

    @Override
    public List<UsbDeviceSummary> list() {
        if (manager == null) {
            return Collections.emptyList();
        }
        List<UsbDeviceSummary> list = new ArrayList<>();
        // In the order Android numbers them
        for (UsbDevice device : new TreeMap<>(manager.getDeviceList()).values()) {
            list.add(summarize(device));
        }
        return list;
    }

    @Override
    public UsbDevice device(String key) {
        Map<String, UsbDevice> devices = manager != null ? manager.getDeviceList() : null;
        return devices != null ? devices.get(key) : null;
    }

    @Override
    public void requestPermission(String key, PermissionCallback callback) {
        UsbDevice device = device(key);
        if (device == null) {
            callback.onAnswer(DENIED);
            return;
        }
        if (manager.hasPermission(device)) {
            callback.onAnswer(GRANTED);
            return;
        }
        answer(DENIED);
        pendingKey = key;
        pendingCallback = callback;
        if (prompts != null) {
            prompts.onUsbPermissionPromptStarting();
        }
        // An explicit intent, so only Starwire's receiver gets it. Mutable, so Android can add
        // the device and its answer.
        Intent intent = new Intent(ACTION_PERMISSION).setPackage(context.getPackageName());
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
        try {
            manager.requestPermission(device, PendingIntent.getBroadcast(context, 1, intent, flags));
        }
        catch (SecurityException e) {
            // Knox and some MDM policies throw here rather than answer no
            LimeLog.warning("USB share: Android wouldn't ask for permission: " + e.getMessage());
            answer(BLOCKED);
        }
    }

    // Answers the waiting permission request, if there is one
    private void answer(int result) {
        PermissionCallback callback = pendingCallback;
        pendingKey = null;
        pendingCallback = null;
        if (callback != null) {
            if (prompts != null) {
                prompts.onUsbPermissionPromptCompleted();
            }
            callback.onAnswer(result);
        }
    }

    /** What Android tells any app about the device: its IDs, class codes and first configuration */
    public static UsbDeviceSummary summarize(UsbDevice device) {
        List<UsbDeviceSummary.Interface> interfaces = new ArrayList<>();
        if (device.getConfigurationCount() > 0) {
            UsbConfiguration configuration = device.getConfiguration(0);
            for (int i = 0; i < configuration.getInterfaceCount(); i++) {
                UsbInterface iface = configuration.getInterface(i);
                List<int[]> endpoints = new ArrayList<>();
                for (int e = 0; e < iface.getEndpointCount(); e++) {
                    UsbEndpoint endpoint = iface.getEndpoint(e);
                    endpoints.add(new int[] {endpoint.getAddress(), endpoint.getAttributes()});
                }
                interfaces.add(new UsbDeviceSummary.Interface(iface.getId(), iface.getAlternateSetting(),
                        iface.getInterfaceClass(), iface.getInterfaceSubclass(), iface.getInterfaceProtocol(),
                        endpoints));
            }
        }
        return new UsbDeviceSummary(device.getDeviceName(), device.getProductName(), device.getVendorId(),
                device.getProductId(), device.getDeviceClass(), device.getDeviceSubclass(),
                device.getDeviceProtocol(), device.getConfigurationCount(), interfaces);
    }
}
