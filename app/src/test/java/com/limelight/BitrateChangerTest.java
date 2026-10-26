package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The bitrate picker's requests to the host. The threads that would ask the host are queued
 * here instead, and each test runs them itself, in the order it wants, so nothing depends on
 * timing. The host is a stand-in that answers at once, or first runs something that stands for
 * what happens while a request is on its way.
 */
public class BitrateChangerTest {
    // The threads BitrateChanger started, not yet run
    private final Deque<Runnable> threads = new ArrayDeque<>();
    private final FakeHost host = new FakeHost();
    // What the user was told, in order
    private final List<String> shown = new ArrayList<>();
    // Thrown once by the next "changed" message, when set
    private RuntimeException showingFails;

    private final BitrateChanger changer = new BitrateChanger(10000, host, threads::add,
            new BitrateChanger.Listener() {
                @Override
                public void onChanged(int appliedKbps) {
                    RuntimeException e = showingFails;
                    showingFails = null;
                    if (e != null) {
                        throw e;
                    }
                    shown.add("changed to " + appliedKbps);
                }

                @Override
                public void onFailed(Exception e) {
                    shown.add("failed: " + e.getMessage());
                }
            });

    private static class FakeHost implements BitrateChanger.Host {
        // Every bitrate asked for, in the order the host got them
        final List<Integer> asked = new ArrayList<>();
        int maxKbps = Integer.MAX_VALUE;
        final Map<Integer, String> failures = new HashMap<>();
        // Runs while the host is working on a request, before it answers
        final Map<Integer, Runnable> whileWorkingOn = new HashMap<>();

        // The bitrate the host streams at now: the last one it was asked for
        int streamingKbps() {
            return Math.min(asked.get(asked.size() - 1), maxKbps);
        }

        @Override
        public int setStreamBitrate(int kbps) throws IOException {
            asked.add(kbps);
            Runnable meanwhile = whileWorkingOn.remove(kbps);
            if (meanwhile != null) {
                meanwhile.run();
            }
            String failure = failures.get(kbps);
            if (failure != null) {
                throw new IOException(failure);
            }
            return Math.min(kbps, maxKbps);
        }
    }

    // Runs the started threads one after another, oldest first, until none are left
    private void runThreads() {
        while (!threads.isEmpty()) {
            threads.removeFirst().run();
        }
    }

    @Test
    public void aPickGoesToTheHostAwayFromTheUiThread() {
        changer.request(20000);
        assertEquals("nothing is sent on the UI thread", Collections.emptyList(), host.asked);
        assertEquals(10000, changer.getCurrentKbps());

        runThreads();
        assertEquals(Collections.singletonList(20000), host.asked);
        assertEquals(Collections.singletonList("changed to 20000"), shown);
        assertEquals(20000, changer.getCurrentKbps());
    }

    @Test
    public void theBitrateTheHostAppliedIsTheOneShown() {
        host.maxKbps = 150000;
        changer.request(300000);
        runThreads();
        assertEquals(Collections.singletonList("changed to 150000"), shown);
        assertEquals(150000, changer.getCurrentKbps());
    }

    @Test
    public void aFailedPickSaysSoAndKeepsTheBitrate() {
        host.failures.put(20000, "timeout");
        changer.request(20000);
        runThreads();
        assertEquals(Collections.singletonList("failed: timeout"), shown);
        assertEquals(10000, changer.getCurrentKbps());
    }

    // The user picks 50 Mbps, opens the picker again while the host is still working on it, and
    // picks 20 Mbps. The host's answer to 20 comes back first.
    @Test
    public void anOlderAnswerCantOverwriteANewerPick() {
        host.whileWorkingOn.put(50000, () -> {
            changer.request(20000);
            runThreads();
        });
        changer.request(50000);
        runThreads();

        assertEquals(20000, host.streamingKbps());
        assertEquals("the last thing shown is the latest pick",
                "changed to 20000", shown.get(shown.size() - 1));
        assertEquals(Collections.singletonList("changed to 20000"), shown);
        assertEquals(20000, changer.getCurrentKbps());
    }

    // Both picks are made before either thread gets to the host, and the newer thread gets there
    // first
    @Test
    public void theHostEndsOnTheLatestPick() {
        changer.request(50000);
        changer.request(20000);
        while (!threads.isEmpty()) {
            threads.removeLast().run();
        }

        assertEquals(20000, host.streamingKbps());
        assertEquals("changed to 20000", shown.get(shown.size() - 1));
        assertEquals(20000, changer.getCurrentKbps());
    }

    // Picks made while one is on its way replace each other; only the latest is sent after it
    @Test
    public void picksInBetweenAreSkipped() {
        host.whileWorkingOn.put(50000, () -> {
            changer.request(30000);
            changer.request(20000);
            runThreads();
        });
        changer.request(50000);
        runThreads();

        assertEquals(Arrays.asList(50000, 20000), host.asked);
        assertEquals(Collections.singletonList("changed to 20000"), shown);
    }

    // The latest pick fails after an older one went through: that's what's shown, and the bitrate
    // kept is the one the host last confirmed
    @Test
    public void aFailedLatestPickIsWhatsShown() {
        host.failures.put(20000, "timeout");
        host.whileWorkingOn.put(50000, () -> {
            changer.request(20000);
            runThreads();
        });
        changer.request(50000);
        runThreads();

        assertEquals(Collections.singletonList("failed: timeout"), shown);
        assertEquals(50000, changer.getCurrentKbps());
    }

    // A thread that dies part way mustn't leave later picks waiting for it
    @Test
    public void aPickAfterTheThreadDiedStillGoes() {
        showingFails = new IllegalStateException("the toast went wrong");
        changer.request(20000);
        try {
            runThreads();
            fail("the thread should have died");
        } catch (IllegalStateException expected) {
            // As an uncaught exception would end the thread
        }

        changer.request(50000);
        assertEquals("a new thread for it", 1, threads.size());
        runThreads();
        assertEquals(Arrays.asList(20000, 50000), host.asked);
        assertEquals(Collections.singletonList("changed to 50000"), shown);
    }

    @Test
    public void picksMadeOneAfterAnotherAllGoThrough() {
        changer.request(20000);
        runThreads();
        changer.request(50000);
        runThreads();
        assertEquals(Arrays.asList(20000, 50000), host.asked);
        assertEquals(Arrays.asList("changed to 20000", "changed to 50000"), shown);
        assertEquals(50000, changer.getCurrentKbps());
    }
}
