package com.limelight;

import static org.junit.Assert.assertEquals;

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
 * here instead, and each test runs them itself, so nothing depends on timing. The host is a
 * stand-in that answers at once.
 */
public class BitrateChangerTest {
    // The threads BitrateChanger started, not yet run
    private final Deque<Runnable> threads = new ArrayDeque<>();
    private final FakeHost host = new FakeHost();
    // What the user was told, in order
    private final List<String> shown = new ArrayList<>();

    private final BitrateChanger changer = new BitrateChanger(10000, host, threads::add,
            new BitrateChanger.Listener() {
                @Override
                public void onChanged(int appliedKbps) {
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

        @Override
        public int setStreamBitrate(int kbps) throws IOException {
            asked.add(kbps);
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
