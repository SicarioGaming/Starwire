package com.limelight.binding.usb.tunnel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** What a tunnel told its listener, in order, with a way to wait for the next of each. */
final class TunnelEvents implements UsbTunnel.Listener {
    final List<String> order = new ArrayList<>();
    private final BlockingQueue<Boolean> sharing = new LinkedBlockingQueue<>();
    private final BlockingQueue<UsbTunnel.Ending> endings = new LinkedBlockingQueue<>();
    // Runs in onSharing(), on the tunnel's thread, before it's recorded
    volatile Runnable whenSharing;

    @Override
    public void onSharing() {
        Runnable r = whenSharing;
        if (r != null) {
            r.run();
        }
        synchronized (order) {
            order.add("sharing");
        }
        sharing.add(true);
    }

    @Override
    public void onEnded(UsbTunnel.Ending ending) {
        synchronized (order) {
            order.add("ended " + ending.kind);
        }
        endings.add(ending);
    }

    void awaitSharing() throws InterruptedException {
        awaitSharing(FakeHelper.SAFETY_MS);
    }

    UsbTunnel.Ending awaitEnded() throws InterruptedException {
        return awaitEnded(FakeHelper.SAFETY_MS);
    }

    // A wait other than SAFETY_MS is only for the test of what a wait that runs out says
    void awaitSharing(long ms) throws InterruptedException {
        if (sharing.poll(ms, TimeUnit.MILLISECONDS) == null) {
            throw new AssertionError(StuckThreads.report("the tunnel never said it was sharing in " + ms + " ms"));
        }
    }

    UsbTunnel.Ending awaitEnded(long ms) throws InterruptedException {
        UsbTunnel.Ending e = endings.poll(ms, TimeUnit.MILLISECONDS);
        if (e == null) {
            throw new AssertionError(StuckThreads.report("the tunnel never said it had ended in " + ms + " ms"));
        }
        return e;
    }

    List<String> order() {
        synchronized (order) {
            return new ArrayList<>(order);
        }
    }
}
