package com.limelight.testing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * The watchdog device-test.sh runs every test under. Time is handed in, so these run its timers
 * by hand instead of waiting two minutes.
 */
@RunWith(AndroidJUnit4.class)
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

    @Test
    public void aTestThatFinishesInTimeIsLeftAlone() {
        watchdog.testStarted(FIRST);
        watchdog.testFinished(FIRST);
        scheduler.runDue();
        assertEquals(new ArrayList<String>(), recorder.events);
        assertEquals(List.of(120_000L), scheduler.delays);
    }

    @Test
    public void aStuckTestIsReportedAndItsActivitiesFinished() {
        watchdog.testStarted(FIRST);
        scheduler.runDue();
        assertEquals(List.of(
                "report first: stuck for 120 s; its activities were finished so the run could go on",
                "finish activities"), recorder.events);
        // What the main thread was doing, and the test's own stack (this thread's)
        assertTrue(recorder.details, recorder.details.startsWith("Main thread ("));
        assertTrue(recorder.details, recorder.details.contains("Main thread's message queue:"));
        assertTrue(recorder.details, recorder.details.contains("Test thread " + Thread.currentThread().getName()));

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
