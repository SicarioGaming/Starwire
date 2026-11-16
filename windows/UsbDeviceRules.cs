// The USB tunnel's device rules, version 1, for the PC's USB helper (GPL-3.0, part of Starwire),
// as docs/dev/usb-protocol.md ("The device rules, version 1") writes them. Starwire applies them
// before it sends SHARE and the helper applies them again to SHARE, reading the descriptors the same
// way, so both reach the same decision. Both are tested against the shared cases in
// windows/tests/vectors/swut-v1-policy.txt.
//
// The checks run in the contract's order and the first one that refuses names the rule. An "ask"
// (a keyboard or mouse) doesn't stop them. Nothing here throws on what a phone sends.
//
// Built with the C# compiler that comes with Windows, like the rest of the helper, so it's C# 5.

using System;
using System.Collections.Generic;

enum UsbVerdict { Allow, Ask, Refuse }

sealed class UsbDecision
{
    public readonly UsbVerdict Verdict;
    // The rule that refused it (Swut.Rule...), 0 otherwise
    public readonly int Rule;

    UsbDecision(UsbVerdict verdict, int rule)
    {
        Verdict = verdict;
        Rule = rule;
    }

    public static readonly UsbDecision Allowed = new UsbDecision(UsbVerdict.Allow, 0);
    public static readonly UsbDecision Asks = new UsbDecision(UsbVerdict.Ask, 0);

    public static UsbDecision Refused(int rule)
    {
        return new UsbDecision(UsbVerdict.Refuse, rule);
    }

    // As the policy cases write it: allow, ask, or refuse and the rule
    public override string ToString()
    {
        return Verdict == UsbVerdict.Allow ? "allow" : Verdict == UsbVerdict.Ask ? "ask" : "refuse " + Rule;
    }
}

static class UsbDeviceRules
{
    // Version 1's vendor list is empty, so every vendor-specific interface is refused. A device goes
    // on it by its vendor and product IDs, after a test with that device and a review.
    static readonly int[][] VendorList = new int[0][];

    const int MaxDepth = 16;

    // One interface descriptor and what follows it, up to the next interface descriptor or IAD
    sealed class Alt
    {
        public int Number, Setting, Class, SubClass, Protocol, NumEndpoints;
        public readonly List<int> EndpointAddresses = new List<int>();
        public readonly List<int> EndpointTypes = new List<int>();
        // The first HID descriptor: its first class descriptor's type and length
        public bool HasHid;
        public int HidFirstType, HidFirstLength;
    }

    sealed class Iad
    {
        public int First, Count, Class, SubClass, Protocol;
    }

    // The helper's decision: an "ask" device without SHARE's flag is refused with rule 5, since
    // only Starwire can ask you
    public static UsbDecision ForHelper(SwutShare share)
    {
        UsbDecision d = Decide(share);
        if (d.Verdict == UsbVerdict.Ask && !share.Confirmed) {
            return UsbDecision.Refused(Swut.RuleUnconfirmedInput);
        }
        return d;
    }

    // The rules' own decision, as both sides reach it: allow, ask, or refuse and the rule
    public static UsbDecision Decide(SwutShare share)
    {
        if (share == null) {
            return UsbDecision.Refused(Swut.RuleUnreadable);
        }
        byte[] dev = share.DeviceDescriptor;
        List<Alt> alts;
        List<Iad> iads;
        // 1. Reading the descriptors
        if (dev == null || dev.Length != Swut.DeviceDescriptor || dev[0] != 18 || dev[1] != 1
                || !Read(share, out alts, out iads)) {
            return UsbDecision.Refused(Swut.RuleUnreadable);
        }
        // 2. One configuration
        if (dev[17] != 1) {
            return UsbDecision.Refused(Swut.RuleConfigurations);
        }
        bool listed = Listed(share.VendorId, share.ProductId);
        // 3. The device's own class codes
        int dc = dev[4], ds = dev[5], dp = dev[6];
        if (!(dc == 0 && ds == 0 && dp == 0) && !(dc == 0xEF && ds == 0x02 && dp == 0x01) && !(dc == 0xFF && listed)) {
            return UsbDecision.Refused(DeviceClassRule(dc, ds));
        }
        bool ask = false;
        // 4. Each IAD, in order
        foreach (Iad iad in iads) {
            UsbDecision d = ClassDecision(iad.Class, iad.SubClass, iad.Protocol, true, listed);
            if (d.Verdict == UsbVerdict.Refuse) {
                return d;
            }
            ask |= d.Verdict == UsbVerdict.Ask;
            foreach (Alt a in alts) {
                if (a.Number >= iad.First && a.Number < iad.First + iad.Count && a.Class != iad.Class) {
                    return UsbDecision.Refused(Swut.RuleUnreadable);
                }
            }
        }
        // 5. Each interface and alternate setting, in order
        bool audio = false;
        foreach (Alt a in alts) {
            audio |= a.Class == 0x01;
        }
        foreach (Alt a in alts) {
            UsbDecision d = ClassDecision(a.Class, a.SubClass, a.Protocol, false, listed);
            if (d.Verdict == UsbVerdict.Refuse) {
                return d;
            }
            ask |= d.Verdict == UsbVerdict.Ask;
            if (!EndpointsFit(a)) {
                return UsbDecision.Refused(Swut.RuleUnreadable);
            }
        }
        // 6. Each HID report descriptor, in the order of its interface in the configuration
        List<int> seen = new List<int>();
        foreach (Alt a in alts) {
            if (seen.Contains(a.Number)) {
                continue;
            }
            seen.Add(a.Number);
            byte[] report = share.ReportFor(a.Number);
            if (report == null) {
                continue;
            }
            UsbDecision d = Report(report, audio);
            if (d.Verdict == UsbVerdict.Refuse) {
                return d;
            }
            ask |= d.Verdict == UsbVerdict.Ask;
        }
        // 7. The strictest of all its parts
        return ask ? UsbDecision.Asks : UsbDecision.Allowed;
    }

    static bool Listed(int vendor, int product)
    {
        foreach (int[] v in VendorList) {
            if (v[0] == vendor && v[1] == product) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 1. reading the descriptors
    static bool Read(SwutShare share, out List<Alt> alts, out List<Iad> iads)
    {
        alts = new List<Alt>();
        iads = new List<Iad>();
        byte[] c = share.Configuration;
        int total = c.Length;
        if (total < 9 || c[0] != 9 || c[1] != 2 || Swut.GetU16(c, 2) != total) {
            return false;
        }
        Alt current = null;
        int at = 9;
        while (at < total) {
            if (total - at < 2) {
                return false;
            }
            int length = c[at];
            int type = c[at + 1];
            if (length < 2 || length > total - at) {
                return false;
            }
            if (type == 1 || type == 2) {
                return false;
            }
            if (type == 4) {
                if (length != 9 || !Closes(current)) {
                    return false;
                }
                current = new Alt();
                current.Number = c[at + 2];
                current.Setting = c[at + 3];
                current.NumEndpoints = c[at + 4];
                current.Class = c[at + 5];
                current.SubClass = c[at + 6];
                current.Protocol = c[at + 7];
                foreach (Alt a in alts) {
                    if (a.Number == current.Number && a.Setting == current.Setting) {
                        return false;
                    }
                }
                alts.Add(current);
            }
            else if (type == 5) {
                if (current == null || !(length == 7 || (length == 9 && current.Class == 0x01))) {
                    return false;
                }
                int address = c[at + 2];
                if ((address & 0x0F) == 0 || current.EndpointAddresses.Contains(address)
                        || current.EndpointAddresses.Count >= current.NumEndpoints) {
                    return false;
                }
                current.EndpointAddresses.Add(address);
                current.EndpointTypes.Add(c[at + 3] & 3);
            }
            else if (type == 0x0B) {
                if (length != 8 || !Closes(current)) {
                    return false;
                }
                current = null;
                Iad iad = new Iad();
                iad.First = c[at + 2];
                iad.Count = c[at + 3];
                iad.Class = c[at + 4];
                iad.SubClass = c[at + 5];
                iad.Protocol = c[at + 6];
                iads.Add(iad);
            }
            else if (type == 0x21 && current != null && current.Class == 0x03) {
                // A HID descriptor: bcdHID, country, the number of class descriptors, then the
                // first one's type and length. A second one in the same setting doesn't fit.
                if (current.HasHid || length < 9 || c[at + 5] < 1) {
                    return false;
                }
                current.HasHid = true;
                current.HidFirstType = c[at + 6];
                current.HidFirstLength = Swut.GetU16(c, at + 7);
            }
            at += length;
        }
        if (!Closes(current)) {
            return false;
        }
        // bNumInterfaces is the number of different interface numbers
        List<int> numbers = new List<int>();
        foreach (Alt a in alts) {
            if (!numbers.Contains(a.Number)) {
                numbers.Add(a.Number);
            }
        }
        if (c[4] != numbers.Count) {
            return false;
        }
        // Each IAD names interfaces that are there, and no interface is named twice
        List<int> named = new List<int>();
        foreach (Iad iad in iads) {
            if (iad.Count < 1) {
                return false;
            }
            for (int n = iad.First; n < iad.First + iad.Count; n++) {
                if (!numbers.Contains(n) || named.Contains(n)) {
                    return false;
                }
                named.Add(n);
            }
        }
        // Every setting of a HID interface has its HID descriptor, and SHARE that interface's
        // report descriptor, exactly that long
        foreach (Alt a in alts) {
            if (a.Class != 0x03) {
                continue;
            }
            byte[] report = share.ReportFor(a.Number);
            if (!a.HasHid || a.HidFirstType != 0x22 || report == null || report.Length != a.HidFirstLength) {
                return false;
            }
        }
        // No report descriptor for an interface that isn't HID or isn't there
        foreach (int n in share.ReportInterfaces) {
            bool hid = false;
            foreach (Alt a in alts) {
                hid |= a.Number == n && a.Class == 0x03;
            }
            if (!hid) {
                return false;
            }
        }
        return true;
    }

    // Exactly bNumEndpoints endpoint descriptors came after this interface descriptor
    static bool Closes(Alt a)
    {
        return a == null || a.EndpointAddresses.Count == a.NumEndpoints;
    }

    // ---------------------------------------------------------------- 3 to 5. class codes
    // The rule for a device whose own class codes name a class (step 3)
    static int DeviceClassRule(int cls, int sub)
    {
        switch (cls) {
            case 0x09: return Swut.RuleHub;
            case 0x08:
            case 0x06: return Swut.RuleStorage;
            case 0x02:
            case 0x0A:
            case 0xE0: return Swut.RuleNetwork;
            case 0xFF: return Swut.RuleUnlistedVendor;
        }
        if (cls == 0xEF && sub == 0x04) {
            return Swut.RuleNetwork;
        }
        return Swut.RuleOtherClass;
    }

    // The class table for an interface, or an IAD's function (whose audio subclass is always 0)
    static UsbDecision ClassDecision(int cls, int sub, int protocol, bool iad, bool listed)
    {
        switch (cls) {
            case 0x01:
                return iad || (sub >= 1 && sub <= 3) ? UsbDecision.Allowed : UsbDecision.Refused(Swut.RuleOtherClass);
            case 0x03:
                // A boot keyboard or mouse asks, whatever its report descriptor says
                return sub == 1 && (protocol == 1 || protocol == 2) ? UsbDecision.Asks : UsbDecision.Allowed;
            case 0xFF:
                return listed ? UsbDecision.Allowed : UsbDecision.Refused(Swut.RuleUnlistedVendor);
            case 0x08:
            case 0x06:
                return UsbDecision.Refused(Swut.RuleStorage);
            case 0x09:
                return UsbDecision.Refused(Swut.RuleHub);
            case 0x02:
            case 0x0A:
            case 0xE0:
                return UsbDecision.Refused(Swut.RuleNetwork);
        }
        if (cls == 0xEF && sub == 0x04) {
            return UsbDecision.Refused(Swut.RuleNetwork);
        }
        return UsbDecision.Refused(Swut.RuleOtherClass);
    }

    const int Control = 0, Isochronous = 1, Bulk = 2, Interrupt = 3;

    // The endpoints each allowed class may have
    static bool EndpointsFit(Alt a)
    {
        int ins = 0, outs = 0;
        for (int i = 0; i < a.EndpointTypes.Count; i++) {
            int type = a.EndpointTypes[i];
            bool input = (a.EndpointAddresses[i] & 0x80) != 0;
            if (a.Class == 0x01 && a.SubClass == 1) {
                if (type != Interrupt || !input || a.EndpointTypes.Count > 1) {
                    return false;
                }
            }
            else if (a.Class == 0x01 && a.SubClass == 2) {
                if (type != Isochronous) {
                    return false;
                }
            }
            else if (a.Class == 0x01 && a.SubClass == 3) {
                if (type != Bulk) {
                    return false;
                }
            }
            else if (a.Class == 0x03) {
                if (type != Interrupt) {
                    return false;
                }
                if (input) {
                    ins++;
                }
                else {
                    outs++;
                }
                if (ins > 1 || outs > 1) {
                    return false;
                }
            }
            else {
                // A vendor-specific interface on the list: version 1's list is empty
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 6. HID report descriptors
    // Read item by item (HID 1.11 section 6.2.2) with the contract's limits, so that every parser
    // finds the same top-level collections with the same usages. Rule 7 if it doesn't read; else
    // the strictest decision of its top-level collections.
    public static UsbDecision Report(byte[] r, bool deviceHasAudio)
    {
        int at = 0;
        int depth = 0;
        long usagePage = 0;
        // Local items since the last main item
        int usages = 0;
        bool range = false;
        bool extended = false;
        long usage = 0;
        bool pageAfterUsage = false;
        int tops = 0;
        bool ask = false;
        UsbDecision refused = null;
        while (at < r.Length) {
            int prefix = r[at];
            if (prefix == 0xFE) {
                return UsbDecision.Refused(Swut.RuleUnreadable);   // a long item
            }
            int size = prefix & 3;
            if (size == 3) {
                size = 4;
            }
            int type = (prefix >> 2) & 3;
            int tag = prefix >> 4;
            if (r.Length - at - 1 < size) {
                return UsbDecision.Refused(Swut.RuleUnreadable);
            }
            long data = 0;
            for (int i = 0; i < size; i++) {
                data |= (long) r[at + 1 + i] << (8 * i);
            }
            at += 1 + size;
            if (type == 0) {
                // Main items
                if (tag == 0x8 || tag == 0x9 || tag == 0xB) {
                    // Input, Output, Feature: only inside a collection
                    if (depth == 0) {
                        return UsbDecision.Refused(Swut.RuleUnreadable);
                    }
                }
                else if (tag == 0xA) {
                    if (depth == 0) {
                        // A top-level collection: an Application, after exactly one Usage
                        if (data != 1 || usages != 1 || range || (!extended && pageAfterUsage)) {
                            return UsbDecision.Refused(Swut.RuleUnreadable);
                        }
                        long page = extended ? usage >> 16 : usagePage;
                        long id = extended ? usage & 0xFFFF : usage;
                        UsbDecision d = TopLevel(page, id, deviceHasAudio);
                        tops++;
                        if (d.Verdict == UsbVerdict.Refuse && refused == null) {
                            refused = d;
                        }
                        ask |= d.Verdict == UsbVerdict.Ask;
                    }
                    depth++;
                    if (depth > MaxDepth) {
                        return UsbDecision.Refused(Swut.RuleUnreadable);
                    }
                }
                else if (tag == 0xC) {
                    if (depth == 0) {
                        return UsbDecision.Refused(Swut.RuleUnreadable);
                    }
                    depth--;
                }
                else {
                    return UsbDecision.Refused(Swut.RuleUnreadable);
                }
                usages = 0;
                range = false;
                extended = false;
                usage = 0;
                pageAfterUsage = false;
            }
            else if (type == 1) {
                // Global items: Usage Page, Logical and Physical Minimum and Maximum, Unit Exponent,
                // Unit, Report Size, Report ID, Report Count. Not Push, Pop or the reserved tags.
                if (tag == 0x0) {
                    if (data > 0xFFFF) {
                        return UsbDecision.Refused(Swut.RuleUnreadable);
                    }
                    usagePage = data;
                    if (usages > 0) {
                        pageAfterUsage = true;
                    }
                }
                else if (tag > 0x9) {
                    return UsbDecision.Refused(Swut.RuleUnreadable);
                }
            }
            else if (type == 2) {
                // Local items: Usage, Usage Minimum and Maximum, the Designator and String Index,
                // Minimum and Maximum. Not Delimiter or the reserved tags.
                if (tag == 0x0) {
                    usages++;
                    extended = size == 4;
                    usage = data;
                    pageAfterUsage = false;
                }
                else if (tag == 0x1 || tag == 0x2) {
                    range = true;
                }
                else if (!(tag == 0x3 || tag == 0x4 || tag == 0x5 || tag == 0x7 || tag == 0x8 || tag == 0x9)) {
                    return UsbDecision.Refused(Swut.RuleUnreadable);
                }
            }
            else {
                return UsbDecision.Refused(Swut.RuleUnreadable);
            }
        }
        if (depth != 0 || tops == 0) {
            return UsbDecision.Refused(Swut.RuleUnreadable);
        }
        if (refused != null) {
            return refused;
        }
        return ask ? UsbDecision.Asks : UsbDecision.Allowed;
    }

    // Windows makes one HID device for each top-level collection and picks its driver by its usage
    static UsbDecision TopLevel(long page, long usage, bool deviceHasAudio)
    {
        if (page == 0x01) {
            if (usage == 0x04 || usage == 0x05 || usage == 0x08) {
                return UsbDecision.Allowed;   // joystick, game pad, multi-axis controller
            }
            if (usage == 0x01 || usage == 0x02 || usage == 0x06 || usage == 0x07 || usage == 0x80) {
                return UsbDecision.Asks;      // pointer, mouse, keyboard, keypad, system control
            }
            return UsbDecision.Refused(Swut.RuleOtherClass);
        }
        if (page == 0x02 || page == 0x05) {
            return UsbDecision.Allowed;       // simulation and game controls
        }
        if (page == 0x0D) {
            return UsbDecision.Asks;          // digitizers
        }
        if (page == 0x0C || page == 0x0B) {
            // Consumer and telephony: a headset's buttons, on a device with an audio interface
            return deviceHasAudio ? UsbDecision.Allowed : UsbDecision.Asks;
        }
        if (page >= 0xFF00 && page <= 0xFFFF) {
            return UsbDecision.Allowed;       // vendor-defined pages
        }
        return UsbDecision.Refused(Swut.RuleOtherClass);
    }
}
