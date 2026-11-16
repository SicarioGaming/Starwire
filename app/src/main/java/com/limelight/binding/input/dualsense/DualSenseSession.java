package com.limelight.binding.input.dualsense;

/**
 * One connection to one DualSense over Bluetooth, from the pad's connect to its disconnect or the
 * end of the stream. It checks the link on connect, then sends the triggers' effects as they
 * change. Anything that fails leaves it on today's path for good, with the reason in status(); a
 * reconnect makes a new session and checks again.
 *
 * Only trigger effects and the pad's trigger strength go this way in 0.3.0. Rumble and the light
 * bar stay on Android's own path, so a phone that drops these reports loses nothing it had before.
 * Android's "true" means it took a report, not that the pad got it.
 *
 * Plain Java and not thread-safe: DualSenseBluetooth calls it on one thread, the JVM tests on
 * theirs, with a fake HidHost.
 */
public final class DualSenseSession {
    /** Reports one change can take: the effects and strength fit in one; this is a safety stop. */
    private static final int MAX_REPORTS_PER_CHANGE = 4;

    private final HidHost host;
    private final TriggerRules rules;
    private final int strengthStep;
    private final HidSender sender = new HidSender();
    private final DualSenseOutput output = new DualSenseOutput();
    private String address;
    private DualSenseStatus status = DualSenseStatus.READY;
    private int reportsSent;

    /**
     * @param host         Android's HID host, or null if its service never answered
     * @param rules        the stream's trigger rules, kept across reconnects
     * @param strengthStep the trigger strength, 1 to 8 (TriggerStrength)
     */
    public DualSenseSession(HidHost host, TriggerRules rules, int strengthStep) {
        this.host = host;
        this.rules = rules;
        this.strengthStep = strengthStep;
    }

    public DualSenseStatus status() {
        return status;
    }

    /** The address of the pad this session sends to, or null before a successful find. */
    public String address() {
        return address;
    }

    /** The call form that worked last, or null. */
    public SendForm sendForm() {
        return sender.winner();
    }

    /** What the last failed call threw, or null. */
    public Throwable lastError() {
        return sender.lastError();
    }

    /** Reports Android took. */
    public int reportsSent() {
        return reportsSent;
    }

    /**
     * Finds the pad and only checks it can be reached, sending nothing (Settings, before Test).
     *
     * @param preferredAddress the pad's address from its InputDevice (PadMatcher.addressFrom), or null
     */
    public DualSenseStatus find(String preferredAddress) {
        if (host == null) {
            return fail(DualSenseStatus.NO_HID_SERVICE);
        }
        if (host.forms().isEmpty()) {
            return fail(DualSenseStatus.NO_CALL_FORM);
        }
        PadMatcher.Result match = PadMatcher.find(host.connectedDevices(), preferredAddress);
        if (match.device == null) {
            return fail(match.status);
        }
        address = match.device.address;
        status = DualSenseStatus.READY;
        return status;
    }

    /**
     * The check on every connect: finds the pad, then sends the strength and what the triggers
     * should run now. The send is the last check: WORKING if Android took it, SEND_FAILED if not.
     */
    public DualSenseStatus connect(String preferredAddress) {
        if (find(preferredAddress) != DualSenseStatus.READY) {
            return status;
        }
        // A new session has a new report builder, so all of this goes out, even if the pad had
        // it before a reconnect: hosts send a game's effect only once, when it changes
        output.setMotorPowerCut(0, TriggerStrength.powerCut(strengthStep));
        rules.applyTo(output);
        return flush();
    }

    /** The host's 0x5503 message. Sent at once while WORKING; otherwise only remembered. */
    public DualSenseStatus onGameMessage(HostTriggerMessage message) {
        rules.onGameMessage(message);
        return sync();
    }

    /**
     * Sends what the rules say the triggers should run now, if that changed. DualSenseBluetooth
     * applies a burst of the host's messages to the rules first and then calls this once, so a
     * host that floods messages costs one send, not one each.
     */
    public DualSenseStatus sync() {
        if (status != DualSenseStatus.WORKING) {
            return status;
        }
        rules.applyTo(output);
        return flush();
    }

    /** The Test button: one effect on both triggers. Needs a found pad. */
    public DualSenseStatus test(TriggerEffect effect) {
        if (status != DualSenseStatus.READY && status != DualSenseStatus.WORKING) {
            return status;
        }
        output.setMotorPowerCut(0, TriggerStrength.powerCut(strengthStep));
        output.setRightTrigger(effect);
        output.setLeftTrigger(effect);
        return flush();
    }

    /**
     * Turns both triggers off, so the pad isn't left with an effect after the stream or a test.
     * Does nothing unless a report went out before.
     */
    public DualSenseStatus release() {
        if (status != DualSenseStatus.WORKING) {
            return status;
        }
        output.setRightTrigger(TriggerEffect.off());
        output.setLeftTrigger(TriggerEffect.off());
        return flush();
    }

    /** The pad went away. Its next connect is a new session. */
    public void lost() {
        address = null;
        status = DualSenseStatus.NO_PAD;
    }

    private DualSenseStatus flush() {
        for (int i = 0; i < MAX_REPORTS_PER_CHANGE && output.hasChanges(); i++) {
            if (!sender.send(host, address, output.nextBluetoothReport())) {
                return fail(DualSenseStatus.SEND_FAILED);
            }
            reportsSent++;
        }
        status = DualSenseStatus.WORKING;
        return status;
    }

    private DualSenseStatus fail(DualSenseStatus reason) {
        status = reason;
        return status;
    }
}
