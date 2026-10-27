package com.limelight.binding.usb.share;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.binding.input.driver.UsbDriverService;
import com.limelight.ui.overlay.OverlayMenuView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * USB sharing during one stream (Experimental), for Game: the stream menu's USB devices page, the
 * question before a keyboard or mouse is shared, and giving the device back when the stream ends
 * or the screen closes. Game calls start() once the stream is up, and stop() when it ends.
 *
 * It also tells the tunnel when the phone's network goes away, so a Wi-Fi drop shows as lost (and
 * offers "Share again") at once, not after the tunnel's 8 s of silence.
 */
public final class StreamUsbShare {
    private static Executor workerForTests;

    private final Activity activity;
    private final OverlayMenuView menu;
    private UsbShareController controller;
    private ExecutorService worker;
    private AlertDialog question;
    private ConnectivityManager.NetworkCallback network;

    public StreamUsbShare(Activity activity, OverlayMenuView menu) {
        this.activity = activity;
        this.menu = menu;
    }

    /**
     * Offers USB devices in the stream menu, if the setting is on and this build and phone can
     * share. prompts hears when Android's permission dialog shows and closes.
     */
    public void start(boolean settingOn, UsbShareTunnel.Target target,
                      UsbDriverService.UsbDriverStateListener prompts) {
        UsbShareBackends.Factory backends = UsbShareBackends.get();
        if (!settingOn || controller != null || backends == null || !backends.isSupported()) {
            return;
        }
        Handler main = new Handler(activity.getMainLooper());
        Executor work = workerForTests;
        if (work == null) {
            // A thread for each connect that needs one, so one that's slow never holds up the next
            worker = Executors.newCachedThreadPool(r -> new Thread(r, "UsbShare"));
            work = worker;
        }
        controller = new UsbShareController(activity, new AndroidUsbDevices(activity, prompts), backends, target,
                new UsbShareController.Ui() {
                    @Override
                    public void onChanged() {
                        menu.refreshUsbDevices();
                    }

                    @Override
                    public void confirmInput(UsbShareController.Entry device, UsbShareController.Answer answer) {
                        ask(device, answer);
                    }

                    @Override
                    public void cancelConfirm() {
                        dismissQuestion();
                    }

                    @Override
                    public void showMessage(String message) {
                        if (!activity.isFinishing()) {
                            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                        }
                    }
                }, main::post, work);
        menu.setUsbDevicesShown(true);
        watchNetwork(main);
    }

    /** Whether the stream menu offers USB devices now */
    public boolean isRunning() {
        return controller != null;
    }

    /** The devices plugged into the phone, for the menu's page */
    public List<OverlayMenuView.UsbItem> items() {
        List<OverlayMenuView.UsbItem> items = new ArrayList<>();
        if (controller != null) {
            for (UsbShareController.Entry e : controller.entries()) {
                items.add(new OverlayMenuView.UsbItem(e.key, e.name, UsbShareText.detail(activity, e)));
            }
        }
        return items;
    }

    /** A device on the menu's page was chosen */
    public void choose(String key) {
        if (controller != null) {
            controller.activate(key);
        }
    }

    /** Gives a shared device back to the phone, and stops offering USB devices: the stream ended. */
    public void stop(int endReason) {
        dismissQuestion();
        if (controller == null) {
            return;
        }
        controller.close(endReason);
        controller = null;
        stopWatchingNetwork();
        if (worker != null) {
            // Lets a connect already running finish; the tunnel then ends it, as it was asked
            worker.shutdown();
            worker = null;
        }
        menu.setUsbDevicesShown(false);
    }

    // A keyboard or mouse is shared only after a yes, every time (docs/dev/usb-protocol.md).
    // Closing the question any other way is a no.
    private void ask(UsbShareController.Entry device, UsbShareController.Answer answer) {
        dismissQuestion();
        boolean[] answered = {false};
        question = new AlertDialog.Builder(activity)
                .setTitle(R.string.usb_share_confirm_title)
                .setMessage(activity.getString(R.string.usb_share_confirm_message, device.name))
                .setPositiveButton(R.string.usb_share_confirm_share, (d, which) -> {
                    answered[0] = true;
                    answer.onAnswer(true);
                })
                .setNegativeButton(R.string.usb_share_confirm_cancel, null)
                .setOnDismissListener(d -> {
                    if (!answered[0]) {
                        answered[0] = true;
                        answer.onAnswer(false);
                    }
                })
                .show();
    }

    // The phone's default network going away; the tunnel's connection goes over it
    private void watchNetwork(Handler main) {
        ConnectivityManager connectivity = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        // Sharing needs Android 9 or later anyway (UsbShareBackends.Factory.isSupported)
        if (connectivity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        network = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLost(Network lost) {
                main.post(() -> {
                    if (controller != null) {
                        controller.networkLost();
                    }
                });
            }
        };
        try {
            connectivity.registerDefaultNetworkCallback(network, main);
        }
        catch (RuntimeException e) {
            // Too many callbacks in the app, say: a drop then shows after 8 s of silence
            network = null;
        }
    }

    private void stopWatchingNetwork() {
        if (network == null) {
            return;
        }
        ConnectivityManager connectivity = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            connectivity.unregisterNetworkCallback(network);
        }
        catch (RuntimeException e) {
            // Never registered
        }
        network = null;
    }

    private void dismissQuestion() {
        if (question != null) {
            AlertDialog q = question;
            question = null;
            q.dismiss();
        }
    }

    /** Runs the share flow's background work here instead of on its own threads; null for its own. Tests only. */
    public static void setWorkerForTests(Executor executor) {
        workerForTests = executor;
    }
}
