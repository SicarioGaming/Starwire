package com.limelight.binding.usb.tunnel;

import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the tunnel's threads were doing when one of the fakes' safety timeouts ran out
 * (FakeHelper, FakeExporter, TunnelEvents), so a rare stall on CI says where it's stuck: each
 * running thread whose name starts with UsbTunnel, its state, the lock it's waiting for and who
 * holds it, and its stack with the locks it holds. It goes in the failure's message, and to
 * System.err too, since a test that expects an IOException takes a timeout for one and goes on.
 * These tests compile against android.jar, which has no java.lang.management, so ThreadMXBean is
 * reached by reflection: they run on a full JDK.
 */
final class StuckThreads {
    private StuckThreads() {
    }

    /** What ran out, then the threads: printed to System.err, and returned for the failure's message. */
    static String report(String what) {
        String report = what + ". What the UsbTunnel threads were doing:\n" + threads();
        System.err.println(report);
        return report;
    }

    /** The same timeout, with the report as its message. */
    static SocketTimeoutException timedOut(String what, SocketTimeoutException e) {
        SocketTimeoutException withReport = new SocketTimeoutException(report(what));
        withReport.initCause(e);
        return withReport;
    }

    /** Each thread named UsbTunnel* that's running now, oldest first. */
    static String threads() {
        try {
            Object threads = Class.forName("java.lang.management.ManagementFactory")
                    .getMethod("getThreadMXBean").invoke(null);
            Object[] infos = (Object[]) Class.forName("java.lang.management.ThreadMXBean")
                    .getMethod("dumpAllThreads", boolean.class, boolean.class).invoke(threads, true, true);
            Class<?> info = Class.forName("java.lang.management.ThreadInfo");
            Method name = info.getMethod("getThreadName");
            Method id = info.getMethod("getThreadId");
            Method state = info.getMethod("getThreadState");
            Method lock = info.getMethod("getLockName");
            Method owner = info.getMethod("getLockOwnerName");
            Method stack = info.getMethod("getStackTrace");
            Method monitors = info.getMethod("getLockedMonitors");
            Method synchronizers = info.getMethod("getLockedSynchronizers");
            Method depth = Class.forName("java.lang.management.MonitorInfo").getMethod("getLockedStackDepth");

            Map<Long, String> found = new TreeMap<>();
            for (Object t : infos) {
                String n = (String) name.invoke(t);
                if (n == null || !n.startsWith("UsbTunnel")) {
                    continue;
                }
                StringBuilder b = new StringBuilder();
                b.append('"').append(n).append("\" id=").append(id.invoke(t)).append(' ').append(state.invoke(t));
                Object waitsFor = lock.invoke(t);
                if (waitsFor != null) {
                    b.append(" on ").append(waitsFor);
                }
                Object heldBy = owner.invoke(t);
                if (heldBy != null) {
                    b.append(" owned by \"").append(heldBy).append('"');
                }
                b.append('\n');
                StackTraceElement[] frames = (StackTraceElement[]) stack.invoke(t);
                Object[] locked = (Object[]) monitors.invoke(t);
                for (int i = 0; i < frames.length; i++) {
                    b.append("\tat ").append(frames[i]).append('\n');
                    for (Object m : locked) {
                        if ((Integer) depth.invoke(m) == i) {
                            b.append("\t- locked ").append(m).append('\n');
                        }
                    }
                }
                for (Object s : (Object[]) synchronizers.invoke(t)) {
                    b.append("\t- holds ").append(s).append('\n');
                }
                found.put((Long) id.invoke(t), b.toString());
            }
            if (found.isEmpty()) {
                return "None were running.\n";
            }
            StringBuilder all = new StringBuilder();
            for (String t : found.values()) {
                all.append(t);
            }
            return all.toString();
        }
        catch (ReflectiveOperationException | RuntimeException e) {
            return "Couldn't read the threads: " + e + "\n";
        }
    }
}
