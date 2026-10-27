package com.limelight.testing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * The watchdog device-test.sh runs every device test under (it lives in src/sharedTest, so it is
 * built into the device tests and into these). Time is handed in, so these run its timers by hand
 * instead of waiting two minutes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class StuckTestWatchdogTest {
    private static final Description FIRST = Description.createTestDescription("com.example.SomeTest", "first");
    private static final Description SECOND = Description.createTestDescription("com.example.SomeTest", "second");

    // Runs, when told to, the tasks that are due and weren't cancelled
    private static final class ManualScheduler implements StuckTestWatchdog.Scheduler {
        private final List<Runnable> pending = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();

        @Override
        public Runnable schedule(Runnable task, long delayMs) {
            pending.add(task);
            delays.add(delayMs);
            return () -> pending.remove(task);
        }

        void runDue() {
            List<Runnable> due = new ArrayList<>(pending);
            pending.clear();
            for (Runnable task : due) {
                task.run();
            }
        }
    }

    private static final class Recorder implements StuckTestWatchdog.Actions {
        final List<String> events = new ArrayList<>();
        String details = "";

        @Override
        public void report(Description test, String summary, String details) {
            events.add("report " + test.getMethodName() + ": " + summary);
            this.details = details;
        }

        @Override
        public void finishActivities() {
            events.add("finish activities");
        }

        @Override
        public void endProcess() {
            events.add("end process");
        }
    }

    private final ManualScheduler scheduler = new ManualScheduler();
    private final Recorder recorder = new Recorder();
    private final StuckTestWatchdog watchdog = new StuckTestWatchdog(scheduler, recorder, 120_000, 30_000);

    // On a device the runner starts each test on its own thread, not the main one. Robolectric
    // runs these on the main thread, so the test being watched gets a thread of its own.
    private final CountDownLatch testOver = new CountDownLatch(1);
    private Thread testThread;

    private void startOnItsOwnThread(Description test) throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        testThread = new Thread(() -> {
            watchdog.testStarted(test);
            started.countDown();
            try {
                testOver.await();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "the watched test");
        testThread.start();
        started.await();
    }

    @After
    public void endTheWatchedTest() throws InterruptedException {
        testOver.countDown();
        if (testThread != null) {
            testThread.join();
        }
    }

    @Test
    public void aTestThatFinishesInTimeIsLeftAlone() {
        watchdog.testStarted(FIRST);
        watchdog.testFinished(FIRST);
        scheduler.runDue();
        assertEquals(new ArrayList<String>(), recorder.events);
        assertEquals(List.of(120_000L), scheduler.delays);
    }

    @Test
    public void aStuckTestIsReportedAndItsActivitiesFinished() throws InterruptedException {
        startOnItsOwnThread(FIRST);
        scheduler.runDue();
        assertEquals(List.of(
                "report first: stuck for 120 s; its activities were finished so the run could go on",
                "finish activities"), recorder.events);
        // What the main thread was doing, and the test's own stack
        assertTrue(recorder.details, recorder.details.startsWith("Main thread ("));
        assertTrue(recorder.details, recorder.details.contains("Main thread's message queue:"));
        assertTrue(recorder.details, recorder.details.contains("Test thread the watched test ("));

        // Finishing its activities let it end: the process stays
        watchdog.testFinished(FIRST);
        scheduler.runDue();
        assertEquals(2, recorder.events.size());
    }

    @Test
    public void aTestStillStuckAfterwardsEndsTheProcess() {
        watchdog.testStarted(FIRST);
        scheduler.runDue();
        scheduler.runDue();
        assertEquals(List.of(
                "report first: stuck for 120 s; its activities were finished so the run could go on",
                "finish activities",
                "report first: still stuck 30 s after its activities were finished: the main thread is blocked, "
                        + "so the process was ended",
                "end process"), recorder.events);
        assertEquals(List.of(120_000L, 30_000L), scheduler.delays);
    }

    @Test
    public void eachTestHasItsOwnClock() {
        watchdog.testStarted(FIRST);
        watchdog.testFinished(FIRST);
        watchdog.testStarted(SECOND);
        scheduler.runDue();
        assertEquals("report second: stuck for 120 s; its activities were finished so the run could go on",
                recorder.events.get(0));
        assertEquals(2, recorder.events.size());
    }

    @Test
    public void theEndOfTheRunStopsTheClock() {
        watchdog.testStarted(FIRST);
        watchdog.testRunFinished(null);
        scheduler.runDue();
        assertEquals(new ArrayList<String>(), recorder.events);
    }
}
