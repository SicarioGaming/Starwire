package com.limelight.binding.usb.tunnel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** What the fakes report when a safety wait runs out (StuckThreads), with threads the test holds still. */
public class StuckThreadsTest {
    // A thread waiting for a lock says what it waits for and who holds it, and the holder's stack
    // says where it took it. Only threads named UsbTunnel* are in it.
    @Test
    public void aThreadWaitingForALockSaysWhoHoldsIt() throws Exception {
        Object lock = new Object();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (lock) {
                holding.countDown();
                await(letGo);
            }
        }, "UsbTunnelHolder");
        Thread waiter = new Thread(() -> {
            synchronized (lock) {
                // Only waits for the lock
            }
        }, "UsbTunnelWaiter");
        Thread other = new Thread(() -> await(letGo), "NotTheTunnel");
        String name = lock.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(lock));
        String waits = "\"UsbTunnelWaiter\" id=" + waiter.threadId() + " BLOCKED on " + name
                + " owned by \"UsbTunnelHolder\"\n";
        String held = "\"UsbTunnelHolder\" id=" + holder.threadId() + " TIMED_WAITING on java.util.concurrent.CountDownLatch$Sync@";
        try {
            holder.start();
            other.start();
            assertTrue(holding.await(FakeHelper.SAFETY_MS, TimeUnit.MILLISECONDS));
            waiter.start();
            // Until both are where the test put them: a thread just started can be held up for a
            // moment by something else, such as a class being loaded
            String report = StuckThreads.threads();
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FakeHelper.SAFETY_MS);
            while (!report.contains(waits) || !report.contains(held)) {
                if (System.nanoTime() > until) {
                    fail("the threads never got stuck: " + report);
                }
                Thread.sleep(1);
                report = StuckThreads.threads();
            }
            int holderAt = report.indexOf(held);
            int holderEnds = report.indexOf("\n\"", holderAt + 1);
            String holderLines = report.substring(holderAt, holderEnds < 0 ? report.length() : holderEnds + 1);
            assertTrue(report, holderLines.contains("\n\t- locked " + name + "\n"));
            assertTrue(report, holderLines.contains(StuckThreadsTest.class.getName() + ".lambda$"));
            assertFalse(report, report.contains("NotTheTunnel"));
        }
        finally {
            letGo.countDown();
            for (Thread t : new Thread[] {holder, waiter, other}) {
                t.join(FakeHelper.SAFETY_MS);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(FakeHelper.SAFETY_MS, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
