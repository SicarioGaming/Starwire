package com.limelight.binding.usb;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;

/**
 * Android's USB permission for one device you chose to share: asked once, answered once. Its own
 * action, so UsbDriverService (Starwire's driver for wired Xbox pads, which asks with
 * com.limelight.USB_PERMISSION) never takes a device that was meant for sharing.
 */
public final class UsbPermission {
    static final String ACTION = "com.limelight.USB_SHARE_PERMISSION";

    public interface Callback {
        /** Called once, on the main thread; granted is false when Android refused or the request failed. */
        void onResult(UsbDevice device, boolean granted);
    }

    private UsbPermission() {
    }

    /** Asks Android for permission to open the device, unless the app already has it. */
    public static void request(Context context, UsbDevice device, Callback callback) {
        Context app = context.getApplicationContext();
        UsbManager usb = (UsbManager) app.getSystemService(Context.USB_SERVICE);
        if (usb == null) {
            callback.onResult(device, false);
            return;
        }
        if (usb.hasPermission(device)) {
            callback.onResult(device, true);
            return;
        }
        Answer answer = new Answer(device.getDeviceName());
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                UsbDevice answered = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                Boolean granted = answer.take(intent.getAction(), answered == null ? null : answered.getDeviceName(),
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false));
                if (granted != null) {
                    app.unregisterReceiver(this);
                    callback.onResult(device, granted);
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        }
        else {
            app.registerReceiver(receiver, filter);
        }
        try {
            // Explicit, for the unexported receiver (and Android 14's rule for mutable intents)
            Intent intent = new Intent(ACTION).setPackage(app.getPackageName());
            usb.requestPermission(device, PendingIntent.getBroadcast(app, 0, intent,
                    pendingIntentFlags(Build.VERSION.SDK_INT)));
        }
        catch (SecurityException e) {
            // Samsung Knox policies can refuse it this way instead of answering (UsbDriverService)
            app.unregisterReceiver(receiver);
            callback.onResult(device, false);
        }
    }

    /** Mutable from Android 12, so Android can add the device and its answer to the intent. */
    static int pendingIntentFlags(int sdk) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (sdk >= 31) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        return flags;
    }

    /** One request's answer: only for its own action and device, and only once. */
    static final class Answer {
        private final String deviceName;
        private boolean answered;

        Answer(String deviceName) {
            this.deviceName = deviceName;
        }

        /** The answer for this request, or null when the broadcast isn't it (or it came already). */
        synchronized Boolean take(String action, String answeredDevice, boolean granted) {
            if (answered || !ACTION.equals(action) || deviceName == null || !deviceName.equals(answeredDevice)) {
                return null;
            }
            answered = true;
            return granted;
        }
    }
}
