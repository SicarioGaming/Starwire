package com.limelight.binding.input.dualsense;

import com.limelight.LimeLog;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * What LimeLog writes while a test runs, read from its java.util.logging logger, the way Android
 * passes it to logcat. Close it at the end of the test.
 */
public final class LimeLogLines extends Handler implements AutoCloseable {
    private final Logger logger = Logger.getLogger(LimeLog.class.getName());
    private final List<String> lines = new ArrayList<>();

    private LimeLogLines() {
    }

    /** Starts listening. */
    public static LimeLogLines listen() {
        LimeLogLines lines = new LimeLogLines();
        lines.logger.addHandler(lines);
        return lines;
    }

    /** Every line so far that starts with the prefix. */
    public synchronized List<String> startingWith(String prefix) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                out.add(line);
            }
        }
        return out;
    }

    /** The trigger effect lines so far (TriggerEffectLog.PREFIX). */
    public List<String> effects() {
        return startingWith(TriggerEffectLog.PREFIX);
    }

    @Override
    public synchronized void publish(LogRecord record) {
        lines.add(record.getMessage());
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
        logger.removeHandler(this);
    }
}
