package com.limelight.binding.usb.share;

/**
 * What the device rules (docs/dev/usb-protocol.md, "The device rules, version 1") decide for a
 * device: shared, shared after you say yes, or refused for one of the rules.
 *
 * A verdict from what Android tells every app about a device is incomplete when the device has a
 * HID interface: its report descriptors decide, and Starwire can only read them once Android
 * lets it open the device.
 */
public final class UsbVerdict {
    public static final int ALLOWED = 0;
    public static final int ASK = 1;
    public static final int REFUSED = 2;

    // The rules, numbered as the contract numbers them (SwutCodec.RULE_*)
    public static final int RULE_HUB = 1;
    public static final int RULE_STORAGE = 2;
    public static final int RULE_NETWORK = 3;
    public static final int RULE_UNLISTED_VENDOR = 4;
    public static final int RULE_UNCONFIRMED_INPUT = 5;
    public static final int RULE_CONFIGURATIONS = 6;
    public static final int RULE_UNREADABLE = 7;
    public static final int RULE_OTHER_CLASS = 8;
    public static final int RULE_MISMATCH = 9;
    public static final int RULE_TOO_FAST = 10;

    public static final UsbVerdict ALLOW = new UsbVerdict(ALLOWED, 0, true);
    public static final UsbVerdict ALLOW_UNCHECKED = new UsbVerdict(ALLOWED, 0, false);
    public static final UsbVerdict ASK_FIRST = new UsbVerdict(ASK, 0, true);
    public static final UsbVerdict ASK_UNCHECKED = new UsbVerdict(ASK, 0, false);

    /** ALLOWED, ASK or REFUSED */
    public final int decision;
    /** For REFUSED, the rule that refused it; otherwise 0 */
    public final int rule;
    /** False while a HID report descriptor still has to be read */
    public final boolean complete;

    private UsbVerdict(int decision, int rule, boolean complete) {
        this.decision = decision;
        this.rule = rule;
        this.complete = complete;
    }

    public static UsbVerdict refused(int rule) {
        return new UsbVerdict(REFUSED, rule, true);
    }

    public boolean isRefused() {
        return decision == REFUSED;
    }

    public boolean asks() {
        return decision == ASK;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof UsbVerdict)) {
            return false;
        }
        UsbVerdict v = (UsbVerdict) o;
        return decision == v.decision && rule == v.rule && complete == v.complete;
    }

    @Override
    public int hashCode() {
        return decision * 31 * 31 + rule * 31 + (complete ? 1 : 0);
    }

    @Override
    public String toString() {
        switch (decision) {
            case ALLOWED:
                return complete ? "allow" : "allow, unchecked";
            case ASK:
                return complete ? "ask" : "ask, unchecked";
            default:
                return "refuse " + rule;
        }
    }
}
