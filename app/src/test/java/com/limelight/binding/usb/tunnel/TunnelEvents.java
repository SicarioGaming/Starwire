package com.limelight.binding.usb.tunnel;

import static org.junit.Assert.assertNotNull;

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

    @Override
    public void onSharing() {
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
        assertNotNull("the tunnel never said it was sharing", sharing.poll(FakeHelper.SAFETY_MS, TimeUnit.MILLISECONDS));
    }

    UsbTunnel.Ending awaitEnded() throws InterruptedException {
        UsbTunnel.Ending e = endings.poll(FakeHelper.SAFETY_MS, TimeUnit.MILLISECONDS);
        assertNotNull("the tunnel never said it had ended", e);
        return e;
    }

    List<String> order() {
        synchronized (order) {
            return new ArrayList<>(order);
        }
    }
}
