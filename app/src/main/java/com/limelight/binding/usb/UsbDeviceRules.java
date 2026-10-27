package com.limelight.binding.usb;

import com.limelight.binding.usb.tunnel.SwutCodec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The USB tunnel's device rules, version 1 (docs/dev/usb-protocol.md, "The device rules, version
 * 1"): whether Starwire may share a USB device with the PC, decided from the descriptors SHARE
 * carries, before anything is exported. The PC helper applies the same rules again, and both are
 * tested against the same cases (swut-v1-policy.txt). The checks run in the contract's order, and
 * the first one that refuses names the rule. Starwire never gives rule 5 (a keyboard or mouse you
 * didn't confirm): it asks instead, and only the helper refuses an "ask" device without the flag.
 */
public final class UsbDeviceRules {
    /** Allow, ask you first, or refuse with a rule (SwutCodec.RULE_*). */
    public static final class Decision {
        public static final int ALLOW = 0;
        public static final int ASK = 1;
        public static final int REFUSE = 2;

        public final int kind;
        public final int rule;

        private Decision(int kind, int rule) {
            this.kind = kind;
            this.rule = rule;
        }

        public boolean refused() {
            return kind == REFUSE;
        }

        /** As the cases file writes it: allow, ask, or refuse and the rule. */
        @Override
        public String toString() {
            return kind == ALLOW ? "allow" : kind == ASK ? "ask" : "refuse " + rule;
        }
    }

    public static final Decision ALLOWED = new Decision(Decision.ALLOW, 0);
    public static final Decision ASKS = new Decision(Decision.ASK, 0);

    public static Decision refusal(int rule) {
        return new Decision(Decision.REFUSE, rule);
    }

    /**
     * What the checks before the report descriptors decided: a refusal, or the HID interfaces whose
     * report descriptors are to be read, each with the length its HID descriptor gives, in the
     * configuration's order. A device that's refused here never has an interface taken from
     * Android to read its reports.
     */
    public static final class Plan {
        public final Decision refusal;
        public final int[] interfaces;
        public final int[] lengths;

        Plan(Decision refusal, int[] interfaces, int[] lengths) {
            this.refusal = refusal;
            this.interfaces = interfaces;
            this.lengths = lengths;
        }
    }

    private static final int CLASS_AUDIO = 0x01;
    private static final int CLASS_HID = 0x03;
    private static final int MAX_DEPTH = 16;

    private UsbDeviceRules() {
    }

    // A refusal found part way through, with its rule
    static final class Refused extends Exception {
        final int rule;

        Refused(int rule) {
            this.rule = rule;
        }

        // A refusal is an answer, not a failure, so it skips the stack trace (the constructor
        // that says so directly needs Android 7)
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    private static Refused unreadable() {
        return new Refused(SwutCodec.RULE_UNREADABLE);
    }

    // One interface descriptor (an interface number and alternate setting) and what follows it
    private static final class Alt {
        int number, alt, endpointCount, cls, sub, protocol;
        final List<int[]> endpoints = new ArrayList<>();   // {bEndpointAddress, bmAttributes}
        boolean hasHid;
        int reportLength = -1;
    }

    private static final class Iad {
        int first, count, cls, sub;
    }

    private static final class Parsed {
        final List<Alt> alts = new ArrayList<>();
        final List<Iad> iads = new ArrayList<>();
        // HID interface numbers in the configuration's order, and the report length each declares
        final Map<Integer, Integer> hidLengths = new java.util.LinkedHashMap<>();
        boolean hasAudio;
    }

    /**
     * The checks before the report descriptors (steps 1 to 5, the report lengths taken as given).
     * A device whose reports couldn't fit in one SHARE, whatever its name and bus id, is refused
     * here (rule 7), before any report is read: reading each one takes an interface from Android.
     */
    public static Plan plan(byte[] device, byte[] configuration) {
        try {
            Parsed p = read(device, configuration);
            beforeReports(device, p);
            int[] interfaces = new int[p.hidLengths.size()];
            int[] lengths = new int[interfaces.length];
            int i = 0;
            for (Map.Entry<Integer, Integer> e : p.hidLengths.entrySet()) {
                interfaces[i] = e.getKey();
                lengths[i++] = e.getValue();
            }
            if (smallestShare(configuration.length, lengths) > SwutCodec.MAX_PAYLOAD) {
                throw unreadable();
            }
            return new Plan(null, interfaces, lengths);
        }
        catch (Refused r) {
            return new Plan(refusal(r.rule), new int[0], new int[0]);
        }
    }

    // SHARE's payload with a one-character bus id and no name: the least these descriptors need
    static long smallestShare(int configurationLength, int[] reportLengths) {
        long length = 1 + 1 + 1 + 1 + SwutCodec.DEVICE_DESCRIPTOR + 2 + configurationLength + 1;
        for (int l : reportLengths) {
            length += 3 + l;
        }
        return length;
    }

    /** The whole decision for what SHARE says about a device. Its flags don't count here. */
    public static Decision decide(SwutCodec.Share share) {
        return decide(share.deviceDescriptor, share.configuration, share.reportInterfaces, share.reports);
    }

    public static Decision decide(byte[] device, byte[] configuration, int[] reportInterfaces, byte[][] reports) {
        try {
            Parsed p = read(device, configuration);
            // Step 1, finished: each HID interface has its report descriptor, exactly as long as its
            // HID descriptors say, and no other interface has one
            Map<Integer, byte[]> byInterface = new HashMap<>();
            for (int i = 0; i < reportInterfaces.length; i++) {
                if (byInterface.put(reportInterfaces[i], reports[i]) != null
                        || !p.hidLengths.containsKey(reportInterfaces[i])) {
                    throw unreadable();
                }
            }
            for (Map.Entry<Integer, Integer> e : p.hidLengths.entrySet()) {
                byte[] report = byInterface.get(e.getKey());
                if (report == null || report.length != e.getValue()) {
                    throw unreadable();
                }
            }
            boolean ask = beforeReports(device, p);
            // Step 6: each report descriptor, in its interface's order
            for (Integer number : p.hidLengths.keySet()) {
                for (int usage : topLevelCollections(byInterface.get(number))) {
                    ask |= usageAsks(usage >>> 16, usage & 0xFFFF, p.hasAudio);
                }
            }
            // Step 7: the strictest of all its parts
            return ask ? ASKS : ALLOWED;
        }
        catch (Refused r) {
            return refusal(r.rule);
        }
    }

    // Steps 2 to 5. True when something asks.
    private static boolean beforeReports(byte[] device, Parsed p) throws Refused {
        // 2. One configuration
        if ((device[17] & 0xFF) != 1) {
            throw new Refused(SwutCodec.RULE_CONFIGURATIONS);
        }
        // 3. The device's own class codes: only these two leave the choice to the interfaces
        int cls = device[4] & 0xFF, sub = device[5] & 0xFF, protocol = device[6] & 0xFF;
        boolean perInterface = cls == 0 && sub == 0 && protocol == 0;
        boolean grouped = cls == 0xEF && sub == 0x02 && protocol == 0x01;
        if (!perInterface && !grouped) {
            int rule = refusedClass(cls, sub);
            throw new Refused(rule != 0 ? rule : SwutCodec.RULE_OTHER_CLASS);
        }
        // 4. Each IAD: its function class as an interface's, then the class of all it names
        for (Iad iad : p.iads) {
            int rule = refusedClass(iad.cls, iad.sub);
            if (rule != 0) {
                throw new Refused(rule);
            }
            if (iad.cls != CLASS_AUDIO && iad.cls != CLASS_HID) {
                throw new Refused(SwutCodec.RULE_OTHER_CLASS);
            }
            for (Alt a : p.alts) {
                if (a.number >= iad.first && a.number < iad.first + iad.count && a.cls != iad.cls) {
                    throw unreadable();
                }
            }
        }
        // 5. Each interface and alternate setting: its class, then whether its endpoints fit it
        boolean ask = false;
        for (Alt a : p.alts) {
            int rule = refusedClass(a.cls, a.sub);
            if (rule != 0) {
                throw new Refused(rule);
            }
            if (a.cls == CLASS_AUDIO) {
                if (a.sub == 1) {          // control: at most one endpoint, interrupt IN
                    if (a.endpoints.size() > 1) {
                        throw unreadable();
                    }
                    for (int[] e : a.endpoints) {
                        if ((e[1] & 3) != 3 || (e[0] & 0x80) == 0) {
                            throw unreadable();
                        }
                    }
                }
                else if (a.sub == 2) {     // streaming: isochronous only
                    for (int[] e : a.endpoints) {
                        if ((e[1] & 3) != 1) {
                            throw unreadable();
                        }
                    }
                }
                else if (a.sub == 3) {     // MIDI: bulk only
                    for (int[] e : a.endpoints) {
                        if ((e[1] & 3) != 2) {
                            throw unreadable();
                        }
                    }
                }
                else {
                    throw new Refused(SwutCodec.RULE_OTHER_CLASS);
                }
            }
            else if (a.cls == CLASS_HID) {
                // Interrupt only, at most one IN and one OUT
                int in = 0, out = 0;
                for (int[] e : a.endpoints) {
                    if ((e[1] & 3) != 3) {
                        throw unreadable();
                    }
                    if ((e[0] & 0x80) != 0) {
                        in++;
                    }
                    else {
                        out++;
                    }
                }
                if (in > 1 || out > 1) {
                    throw unreadable();
                }
                // A boot keyboard or mouse asks, whatever its report descriptor says
                if (a.sub == 1 && (a.protocol == 1 || a.protocol == 2)) {
                    ask = true;
                }
            }
            else {
                throw new Refused(SwutCodec.RULE_OTHER_CLASS);
            }
        }
        return ask;
    }

    // The rule a class refuses with (hub, storage, network, vendor), or 0 for one the tables go on
    // to decide. The vendor list is empty in version 1, so every vendor-specific class is rule 4.
    private static int refusedClass(int cls, int sub) {
        switch (cls) {
            case 0x09:
                return SwutCodec.RULE_HUB;
            case 0x08:
            case 0x06:
                return SwutCodec.RULE_STORAGE;
            case 0x02:
            case 0x0A:
            case 0xE0:
                return SwutCodec.RULE_NETWORK;
            case 0xEF:
                return sub == 0x04 ? SwutCodec.RULE_NETWORK : 0;
            case 0xFF:
                return SwutCodec.RULE_UNLISTED_VENDOR;
            default:
                return 0;
        }
    }

    // Step 1: the descriptors read, and fit together (all but the report descriptors themselves)
    private static Parsed read(byte[] device, byte[] c) throws Refused {
        if (device == null || device.length != SwutCodec.DEVICE_DESCRIPTOR || (device[0] & 0xFF) != 18
                || device[1] != 1) {
            throw unreadable();
        }
        if (c == null || c.length < SwutCodec.MIN_CONFIGURATION || c.length > SwutCodec.MAX_CONFIGURATION
                || (c[0] & 0xFF) != 9 || c[1] != 2 || u16(c, 2) != c.length) {
            throw unreadable();
        }
        Parsed p = new Parsed();
        Set<Integer> settings = new HashSet<>();
        Alt current = null;
        int at = 9;
        while (at < c.length) {
            if (c.length - at < 2) {
                throw unreadable();
            }
            int length = c[at] & 0xFF, type = c[at + 1] & 0xFF;
            if (length < 2 || at + length > c.length || type == 1 || type == 2) {
                throw unreadable();
            }
            if (type == 4) {                       // an interface
                if (length != 9) {
                    throw unreadable();
                }
                close(current);
                current = new Alt();
                current.number = c[at + 2] & 0xFF;
                current.alt = c[at + 3] & 0xFF;
                current.endpointCount = c[at + 4] & 0xFF;
                current.cls = c[at + 5] & 0xFF;
                current.sub = c[at + 6] & 0xFF;
                current.protocol = c[at + 7] & 0xFF;
                if (!settings.add(current.number << 8 | current.alt)) {
                    throw unreadable();
                }
                p.alts.add(current);
                p.hasAudio |= current.cls == CLASS_AUDIO;
            }
            else if (type == 0x0B) {               // an interface association
                if (length != 8) {
                    throw unreadable();
                }
                close(current);
                current = null;
                Iad iad = new Iad();
                iad.first = c[at + 2] & 0xFF;
                iad.count = c[at + 3] & 0xFF;
                iad.cls = c[at + 4] & 0xFF;
                iad.sub = c[at + 5] & 0xFF;
                p.iads.add(iad);
            }
            else if (type == 5) {                  // an endpoint
                boolean audio = current != null && current.cls == CLASS_AUDIO;
                if (current == null || !(length == 7 || (length == 9 && audio))) {
                    throw unreadable();
                }
                int address = c[at + 2] & 0xFF;
                if ((address & 0x0F) == 0) {
                    throw unreadable();
                }
                for (int[] e : current.endpoints) {
                    if (e[0] == address) {
                        throw unreadable();
                    }
                }
                current.endpoints.add(new int[] {address, c[at + 3] & 0xFF});
            }
            else if (type == 0x21 && current != null && current.cls == CLASS_HID && !current.hasHid) {
                // The HID descriptor: its first class descriptor must be the report descriptor
                int count = length >= 6 ? c[at + 5] & 0xFF : 0;
                if (length < 9 || count < 1 || length < 6 + 3 * count || (c[at + 6] & 0xFF) != 0x22) {
                    throw unreadable();
                }
                current.hasHid = true;
                current.reportLength = u16(c, at + 7);
            }
            at += length;
        }
        close(current);

        Set<Integer> numbers = new HashSet<>();
        for (Alt a : p.alts) {
            numbers.add(a.number);
        }
        if ((c[4] & 0xFF) != numbers.size()) {
            throw unreadable();
        }
        Set<Integer> named = new HashSet<>();
        for (Iad iad : p.iads) {
            if (iad.count < 1) {
                throw unreadable();
            }
            for (int n = iad.first; n < iad.first + iad.count; n++) {
                if (!numbers.contains(n) || !named.add(n)) {
                    throw unreadable();
                }
            }
        }
        // Every alternate setting of a HID interface has a HID descriptor, and they agree on the
        // report descriptor's length, which SHARE can carry
        for (Alt a : p.alts) {
            if (a.cls != CLASS_HID) {
                continue;
            }
            if (!a.hasHid || a.reportLength < 1 || a.reportLength > SwutCodec.MAX_REPORT) {
                throw unreadable();
            }
            Integer before = p.hidLengths.put(a.number, a.reportLength);
            if (before != null && before != a.reportLength) {
                throw unreadable();
            }
        }
        // SHARE carries at most 16 report descriptors, so a device with more HID interfaces can't
        // have them all. Refused here, before Starwire reads any of them.
        if (p.hidLengths.size() > SwutCodec.MAX_REPORTS) {
            throw unreadable();
        }
        return p;
    }

    // An alternate setting ends at the next interface or IAD, or the configuration's end, with
    // exactly as many endpoints as it said
    private static void close(Alt a) throws Refused {
        if (a != null && a.endpoints.size() != a.endpointCount) {
            throw unreadable();
        }
    }

    /**
     * Step 6's reading of a HID report descriptor (HID 1.11 section 6.2.2, with the contract's
     * limits): the page and usage of each top-level collection, as page << 16 | usage.
     */
    static int[] topLevelCollections(byte[] r) throws Refused {
        List<Integer> found = new ArrayList<>();
        int depth = 0;
        long usagePage = 0;
        // Local items since the last main item
        int usages = 0;
        boolean range = false, pageAfterUsage = false, extended = false;
        long usage = 0;
        int at = 0;
        while (at < r.length) {
            int prefix = r[at] & 0xFF;
            if (prefix == 0xFE) {                  // a long item
                throw unreadable();
            }
            int size = prefix & 3;
            if (size == 3) {
                size = 4;
            }
            int type = (prefix >> 2) & 3, tag = prefix >> 4;
            if (r.length - at - 1 < size) {
                throw unreadable();
            }
            long value = 0;
            for (int i = 0; i < size; i++) {
                value |= (long) (r[at + 1 + i] & 0xFF) << (8 * i);
            }
            at += 1 + size;
            if (type == 0) {                       // main
                switch (tag) {
                    case 0x8:                      // Input
                    case 0x9:                      // Output
                    case 0xB:                      // Feature
                        if (depth == 0) {
                            throw unreadable();
                        }
                        break;
                    case 0xA:                      // Collection
                        if (depth == 0) {
                            // A top-level collection: an Application, after exactly one Usage
                            if (value != 1 || usages != 1 || range || (!extended && pageAfterUsage)) {
                                throw unreadable();
                            }
                            long page = extended ? usage >>> 16 : usagePage;
                            found.add((int) (page << 16 | (usage & 0xFFFF)));
                        }
                        if (++depth > MAX_DEPTH) {
                            throw unreadable();
                        }
                        break;
                    case 0xC:                      // End Collection
                        if (depth == 0) {
                            throw unreadable();
                        }
                        depth--;
                        break;
                    default:
                        throw unreadable();
                }
                usages = 0;
                range = false;
                pageAfterUsage = false;
                extended = false;
            }
            else if (type == 1) {                  // global
                if (tag == 0) {                    // Usage Page
                    if (value > 0xFFFF) {
                        throw unreadable();
                    }
                    usagePage = value;
                    pageAfterUsage |= usages > 0;
                }
                else if (tag > 9) {                // Push, Pop and the reserved tags
                    throw unreadable();
                }
            }
            else if (type == 2) {                  // local
                if (tag == 0) {                    // Usage
                    usages++;
                    usage = value;
                    extended = size == 4;
                }
                else if (tag == 1 || tag == 2) {   // Usage Minimum and Maximum
                    range = true;
                }
                else if (tag == 6 || tag > 9) {    // Delimiter and the reserved tags
                    throw unreadable();
                }
            }
            else {                                 // the reserved item type
                throw unreadable();
            }
        }
        if (depth != 0 || found.isEmpty()) {
            throw unreadable();
        }
        int[] out = new int[found.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = found.get(i);
        }
        return out;
    }

    // The top-level collections' table: true when it asks, a Refused when it's another kind of
    // device, false when it's allowed
    private static boolean usageAsks(int page, int usage, boolean hasAudio) throws Refused {
        if ((page == 0x01 && (usage == 0x04 || usage == 0x05 || usage == 0x08)) || page == 0x02 || page == 0x05) {
            return false;                          // a pad, wheel or stick
        }
        if ((page == 0x01 && (usage == 0x01 || usage == 0x02 || usage == 0x06 || usage == 0x07 || usage == 0x80))
                || page == 0x0D) {
            return true;                           // a keyboard, mouse or digitizer
        }
        if (page == 0x0C || page == 0x0B) {
            return !hasAudio;                      // a headset's buttons, or else ask
        }
        if (page >= 0xFF00) {
            return false;                          // vendor-defined pages
        }
        throw new Refused(SwutCodec.RULE_OTHER_CLASS);
    }

    private static int u16(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8;
    }
}
