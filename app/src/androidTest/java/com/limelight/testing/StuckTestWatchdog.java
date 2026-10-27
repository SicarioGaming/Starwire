package com.limelight.testing;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.Log;
import android.util.StringBuilderPrinter;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.RunListener;

import java.util.Timer;
import java.util.TimerTask;

/**
 * Turns a test that hangs into a test that fails, and says what the app's main thread was doing.
 *
 * ActivityScenario waits for the app's main thread to go idle, with no time limit, before it
 * looks at a screen and before it closes one. On the Android 11 phone emulators (the atd-30,
 * handheld-short-30 and arm64-translated-30 legs, 2026-10-23) it never went idle once the games
 * screen had opened, so the first test to open it waited until device-test.sh gave up 40 minutes
 * later, and the other 131 tests never ran. Nothing in the logs said why.
 *
 * device-test.sh adds this listener to every run (-e listener). Once one test has run for
 * starwire.stuckSeconds (90 unless the run says otherwise, three times the slowest test seen on
 * the emulators), it:
 *
 * <ol>
 *   <li>reports the main thread's stack, the messages waiting in its queue and the test's own
 *       stack, to logcat (tag StuckTest) and to the run's output, where parse-instrument.sh turns
 *       the test into a failure even if it passes after this;</li>
 *   <li>finishes the test's activities, so a screen that keeps the main thread busy goes, the
 *       wait ends, and the run carries on with the next test;</li>
 *   <li>if the test still hasn't finished 30 seconds later, the main thread is blocked outright:
 *       it reports again and ends the process, so the run stops now and not 40 minutes on.</li>
 * </ol>
 */
public class StuckTestWatchdog extends RunListener {
    static final String TAG = "StuckTest";
    static final String STATUS_KEY = "stuck";
    static final long DEFAULT_STUCK_MS = 90_000;
    static final long GRACE_MS = 30_000;

    /** Runs a task later; the tests hand in one they run by hand. */
    interface Scheduler {
        /** Returns what cancels it. */
        Runnable schedule(Runnable task, long delayMs);
    }

    /** What the watchdog does to the app. The tests hand in one that only records. */
    interface Actions {
        void report(Description test, String summary, String details);
        void finishActivities();
        void endProcess();
    }

    private final Scheduler scheduler;
    private final Actions actions;
    private final long stuckMs;
    private final long graceMs;

    private final Object lock = new Object();
    private Description running;
    private Thread testThread;
    private Runnable cancel;

    public StuckTestWatchdog() {
        this(new TimerScheduler(), new AppActions(), stuckMsFromArguments(), GRACE_MS);
    }

    StuckTestWatchdog(Scheduler scheduler, Actions actions, long stuckMs, long graceMs) {
        this.scheduler = scheduler;
        this.actions = actions;
        this.stuckMs = stuckMs;
        this.graceMs = graceMs;
    }

    private static long stuckMsFromArguments() {
        String seconds = InstrumentationRegistry.getArguments().getString("starwire.stuckSeconds");
        try {
            return seconds == null ? DEFAULT_STUCK_MS : Math.max(1, Long.parseLong(seconds.trim())) * 1000;
        }
        catch (NumberFormatException e) {
            return DEFAULT_STUCK_MS;
        }
    }

    @Override
    public void testStarted(Description description) {
        synchronized (lock) {
            stopWatching();
            running = description;
            testThread = Thread.currentThread();
            cancel = scheduler.schedule(() -> stuck(description), stuckMs);
        }
    }

    @Override
    public void testFinished(Description description) {
        synchronized (lock) {
            if (description.equals(running)) {
                stopWatching();
            }
        }
    }

    @Override
    public void testRunFinished(Result result) {
        synchronized (lock) {
            stopWatching();
        }
    }

    private void stopWatching() {
        if (cancel != null) {
            cancel.run();
            cancel = null;
        }
        running = null;
        testThread = null;
    }

    private void stuck(Description description) {
        Thread thread;
        synchronized (lock) {
            if (!description.equals(running)) {
                return;
            }
            thread = testThread;
            cancel = scheduler.schedule(() -> stillStuck(description), graceMs);
        }
        actions.report(description, "stuck for " + seconds(stuckMs) + " s; its activities were finished so the run could go on",
                threads(thread));
        actions.finishActivities();
    }

    private void stillStuck(Description description) {
        Thread thread;
        synchronized (lock) {
            if (!description.equals(running)) {
                return;
            }
            thread = testThread;
        }
        actions.report(description, "still stuck " + seconds(graceMs) + " s after its activities were finished: the main "
                + "thread is blocked, so the process was ended", threads(thread));
        actions.endProcess();
    }

    private static long seconds(long ms) {
        return ms / 1000;
    }

    // The main thread's state, stack and waiting messages, then the test thread's stack
    static String threads(Thread testThread) {
        StringBuilder report = new StringBuilder();
        Looper mainLooper = Looper.getMainLooper();
        Thread main = mainLooper.getThread();
        report.append("Main thread (").append(main.getState()).append("):\n");
        appendStack(report, main.getStackTrace());
        report.append("Main thread's message queue:\n");
        mainLooper.dump(new StringBuilderPrinter(report), "  ");
        report.append('\n');
        if (testThread != null && testThread != main) {
            report.append("Test thread ").append(testThread.getName()).append(" (").append(testThread.getState()).append("):\n");
            appendStack(report, testThread.getStackTrace());
        }
        return report.toString();
    }

    private static void appendStack(StringBuilder report, StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            report.append("    at ").append(frame).append('\n');
        }
    }

    private static final class TimerScheduler implements Scheduler {
        private final Timer timer = new Timer(TAG, true);

        @Override
        public Runnable schedule(Runnable task, long delayMs) {
            TimerTask timerTask = new TimerTask() {
                @Override
                public void run() {
                    task.run();
                }
            };
            timer.schedule(timerTask, delayMs);
            return timerTask::cancel;
        }
    }

    private static final class AppActions implements Actions {
        @Override
        public void report(Description test, String summary, String details) {
            String name = test.getClassName() + "#" + test.getMethodName();
            Log.e(TAG, name + ": " + summary);
            for (String line : details.split("\n")) {
                Log.e(TAG, line);
            }
            // In the run's output too: logcat can drop a busy app's lines
            Bundle status = new Bundle();
            status.putString(Instrumentation.REPORT_KEY_IDENTIFIER, TAG);
            status.putString("class", test.getClassName());
            status.putString("test", test.getMethodName());
            status.putString(STATUS_KEY, summary);
            status.putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\n" + name + ": " + summary + "\n" + details);
            InstrumentationRegistry.getInstrumentation().sendStatus(1, status);
        }

        @Override
        public void finishActivities() {
            Handler handler = new Handler(Looper.getMainLooper());
            Message message = Message.obtain(handler, () -> {
                for (Stage stage : Stage.values()) {
                    if (stage == Stage.DESTROYED) {
                        continue;
                    }
                    for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)) {
                        Log.e(TAG, "finishing " + activity);
                        activity.finish();
                    }
                }
            });
            // Ahead of everything else, and past a pending redraw's barrier (Android 5.1 and later)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                message.setAsynchronous(true);
            }
            handler.sendMessageAtFrontOfQueue(message);
        }

        @Override
        public void endProcess() {
            Process.killProcess(Process.myPid());
        }
    }
}
