package com.limelight.binding.input.dualsense;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Sends a report through the first hidden call form that works, and remembers it. The next report
 * tries that form first. If it fails, the others are tried in order; if every form fails, nothing
 * is remembered and send returns false. Ported from Moonlight V+ by qiin2333 (GPL-3.0),
 * AndroidBluetoothHidHostTransport.kt:112-136 at 5840bab3.
 *
 * A form that throws counts as a failure. Plain Java and not thread-safe.
 */
public final class HidSender {
    private SendForm winner;
    private Throwable lastError;

    /** The form that worked last, or null. */
    public SendForm winner() {
        return winner;
    }

    /** What the last failed call threw, or null. For the log. */
    public Throwable lastError() {
        return lastError;
    }

    /** True if Android took the report in some form. */
    public boolean send(HidHost host, String address, byte[] report) {
        for (SendForm form : order(host.forms())) {
            boolean taken;
            try {
                taken = host.call(form, address, report);
            } catch (Throwable t) {
                lastError = t;
                taken = false;
            }
            if (taken) {
                winner = form;
                return true;
            }
        }
        winner = null;
        return false;
    }

    // The winner first, then the rest in SendForm's order, only the ones this device has
    List<SendForm> order(Set<SendForm> available) {
        List<SendForm> order = new ArrayList<>();
        if (winner != null && available.contains(winner)) {
            order.add(winner);
        }
        for (SendForm form : SendForm.values()) {
            if (form != winner && available.contains(form)) {
                order.add(form);
            }
        }
        return order;
    }
}
