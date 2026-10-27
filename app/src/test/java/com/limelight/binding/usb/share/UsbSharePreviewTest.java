package com.limelight.binding.usb.share;

import static com.limelight.binding.usb.share.FakeUsb.BULK;
import static com.limelight.binding.usb.share.FakeUsb.INTERRUPT;
import static com.limelight.binding.usb.share.FakeUsb.ISOCHRONOUS;
import static com.limelight.binding.usb.share.FakeUsb.device;
import static com.limelight.binding.usb.share.FakeUsb.ep;
import static com.limelight.binding.usb.share.FakeUsb.iface;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The device rules as the USB devices list applies them, from what Android shows before
 * permission: one case for each class the contract's table names, and for the order its checks
 * run in (docs/dev/usb-protocol.md, "The device rules, version 1").
 */
public class UsbSharePreviewTest {
    private static UsbDeviceSummary.Interface hid(int subclass, int protocol) {
        return iface(0, 0, 0x03, subclass, protocol, ep(0x81, INTERRUPT));
    }

    private static UsbDeviceSummary one(UsbDeviceSummary.Interface i) {
        return device("d", "Device", 0, 0, 0, 1, i);
    }

    private static void refused(int rule, UsbDeviceSummary d) {
        assertEquals(UsbVerdict.refused(rule), UsbSharePreview.judge(d));
    }

    // Starwire's own USB driver (UsbDriverService) only ever takes Xbox pads and the Xbox 360
    // wireless receiver, which are all vendor-specific: the rules refuse them, so sharing never
    // fights that driver over a device
    @Test
    public void xboxPadsAreRefusedAsVendorSpecific() {
        // Xbox One pad, Xbox 360 pad, Xbox 360 wireless receiver
        refused(UsbVerdict.RULE_UNLISTED_VENDOR, device("d", null, 0xFF, 0x47, 0xD0, 1,
                iface(0, 0, 0xFF, 0x47, 0xD0, ep(0x81, INTERRUPT), ep(0x02, INTERRUPT))));
        refused(UsbVerdict.RULE_UNLISTED_VENDOR, device("d", null, 0xFF, 0xFF, 0xFF, 1,
                iface(0, 0, 0xFF, 0x5D, 0x01, ep(0x81, INTERRUPT), ep(0x02, INTERRUPT))));
        refused(UsbVerdict.RULE_UNLISTED_VENDOR, device("d", null, 0, 0, 0, 1,
                iface(0, 0, 0xFF, 0x5D, 0x81, ep(0x81, INTERRUPT), ep(0x01, INTERRUPT))));
    }

    @Test
    public void padsWaitForTheirReportDescriptor() {
        assertEquals(UsbVerdict.ALLOW_UNCHECKED, UsbSharePreview.judge(FakeUsb.pad("p")));
        assertEquals(UsbVerdict.ALLOW_UNCHECKED, UsbSharePreview.judge(one(hid(0, 0))));
    }

    @Test
    public void bootKeyboardsAndMiceAsk() {
        assertEquals(UsbVerdict.ASK_UNCHECKED, UsbSharePreview.judge(one(hid(1, 1))));
        assertEquals(UsbVerdict.ASK_UNCHECKED, UsbSharePreview.judge(one(hid(1, 2))));
        // Subclass 1 with another protocol is a plain HID interface
        assertEquals(UsbVerdict.ALLOW_UNCHECKED, UsbSharePreview.judge(one(hid(1, 0))));
        // A pad that is also a boot keyboard asks
        assertEquals(UsbVerdict.ASK_UNCHECKED, UsbSharePreview.judge(device("d", null, 0, 0, 0, 1,
                hid(0, 0), iface(1, 0, 0x03, 1, 1, ep(0x82, INTERRUPT)))));
    }

    // Android doesn't show a USB 3 headset's speed, or an IAD that doesn't match its interfaces,
    // so even audio is only checked once it's shared
    @Test
    public void audioIsCheckedWhenShared() {
        assertEquals(UsbVerdict.ALLOW_UNCHECKED, UsbSharePreview.judge(FakeUsb.headset("h")));
        assertEquals(UsbVerdict.ALLOW_UNCHECKED, UsbSharePreview.judge(one(iface(0, 0, 0x01, 3, 0, ep(0x81, BULK), ep(0x01, BULK)))));
        // A headset's buttons are HID: their report descriptor decides
        assertEquals(UsbVerdict.ALLOW_UNCHECKED, UsbSharePreview.judge(device("d", null, 0, 0, 0, 1,
                iface(0, 0, 0x01, 1, 0), iface(1, 0, 0x01, 2, 0), iface(2, 0, 0x03, 0, 0, ep(0x81, INTERRUPT)))));
    }

    @Test
    public void theDevicesOwnClassCodes() {
        // Grouped by IADs
        assertEquals(UsbVerdict.ALLOW_UNCHECKED, UsbSharePreview.judge(device("d", null, 0xEF, 0x02, 0x01, 1,
                iface(0, 0, 0x01, 1, 0))));
        refused(UsbVerdict.RULE_HUB, device("d", null, 0x09, 0, 0, 1, iface(0, 0, 0x09, 0, 0, ep(0x81, INTERRUPT))));
        refused(UsbVerdict.RULE_STORAGE, device("d", null, 0x08, 0, 0, 1));
        refused(UsbVerdict.RULE_NETWORK, device("d", null, 0x02, 0, 0, 1));
        refused(UsbVerdict.RULE_UNLISTED_VENDOR, device("d", null, 0xFF, 0xFF, 0xFF, 1));
        // A class that's fine for an interface isn't for the whole device
        refused(UsbVerdict.RULE_OTHER_CLASS, device("d", null, 0x03, 0, 0, 1, hid(0, 0)));
        refused(UsbVerdict.RULE_OTHER_CLASS, device("d", null, 0x01, 0, 0, 1));
        refused(UsbVerdict.RULE_OTHER_CLASS, device("d", null, 0xEF, 0x01, 0x01, 1));
        refused(UsbVerdict.RULE_NETWORK, device("d", null, 0xEF, 0x04, 0x01, 1));
        // 00 class with a subclass isn't "each interface gives its class"
        refused(UsbVerdict.RULE_OTHER_CLASS, device("d", null, 0x00, 0x01, 0x00, 1));
    }

    @Test
    public void eachInterfaceClass() {
        refused(UsbVerdict.RULE_STORAGE, FakeUsb.storage("s"));
        refused(UsbVerdict.RULE_STORAGE, one(iface(0, 0, 0x06, 1, 1, ep(0x81, BULK))));
        refused(UsbVerdict.RULE_HUB, one(iface(0, 0, 0x09, 0, 0, ep(0x81, INTERRUPT))));
        refused(UsbVerdict.RULE_NETWORK, one(iface(0, 0, 0x02, 2, 1, ep(0x81, INTERRUPT))));
        refused(UsbVerdict.RULE_NETWORK, one(iface(0, 0, 0x0A, 0, 0, ep(0x81, BULK))));
        refused(UsbVerdict.RULE_NETWORK, one(iface(0, 0, 0xE0, 1, 3, ep(0x81, INTERRUPT))));
        refused(UsbVerdict.RULE_NETWORK, one(iface(0, 0, 0xEF, 0x04, 0x01, ep(0x81, INTERRUPT))));
        // Xbox pads by cable among them: the vendor list is empty in version 1
        refused(UsbVerdict.RULE_UNLISTED_VENDOR, one(iface(0, 0, 0xFF, 0x47, 0xD0, ep(0x81, INTERRUPT))));
        // Firmware update, video, audio with no subclass
        refused(UsbVerdict.RULE_OTHER_CLASS, one(iface(0, 0, 0xFE, 1, 2)));
        refused(UsbVerdict.RULE_OTHER_CLASS, one(iface(0, 0, 0x0E, 1, 0, ep(0x81, INTERRUPT))));
        refused(UsbVerdict.RULE_OTHER_CLASS, one(iface(0, 0, 0x01, 0, 0)));
        refused(UsbVerdict.RULE_OTHER_CLASS, one(iface(0, 0, 0xEF, 0x01, 0x01)));
    }

    @Test
    public void endpointsMustFitTheirClass() {
        refused(UsbVerdict.RULE_UNREADABLE, one(iface(0, 0, 0x03, 0, 0, ep(0x81, BULK))));
        refused(UsbVerdict.RULE_UNREADABLE, one(iface(0, 0, 0x03, 0, 0, ep(0x81, INTERRUPT), ep(0x82, INTERRUPT))));
        refused(UsbVerdict.RULE_UNREADABLE, one(iface(0, 0, 0x01, 1, 0, ep(0x01, INTERRUPT))));
        refused(UsbVerdict.RULE_UNREADABLE, one(iface(0, 0, 0x01, 1, 0, ep(0x81, INTERRUPT), ep(0x82, INTERRUPT))));
        refused(UsbVerdict.RULE_UNREADABLE, one(iface(0, 0, 0x01, 2, 0, ep(0x01, BULK))));
        refused(UsbVerdict.RULE_UNREADABLE, one(iface(0, 0, 0x01, 3, 0, ep(0x01, ISOCHRONOUS))));
    }

    @Test
    public void descriptorsThatDontAddUp() {
        // The same interface and alternate setting twice
        refused(UsbVerdict.RULE_UNREADABLE, device("d", null, 0, 0, 0, 1, hid(0, 0), hid(0, 0)));
        // Endpoint 0, and one endpoint twice in an alternate setting
        refused(UsbVerdict.RULE_UNREADABLE, one(iface(0, 0, 0x03, 0, 0, ep(0x80, INTERRUPT))));
        refused(UsbVerdict.RULE_UNREADABLE, one(iface(0, 0, 0x01, 2, 0, ep(0x01, ISOCHRONOUS), ep(0x01, ISOCHRONOUS))));
        refused(UsbVerdict.RULE_UNREADABLE, device("d", null, 0, 0, 0, 0));
    }

    @Test
    public void moreThanOneConfiguration() {
        refused(UsbVerdict.RULE_CONFIGURATIONS, device("d", null, 0, 0, 0, 2, hid(0, 0)));
    }

    // The first check that refuses names the rule, in the contract's order
    @Test
    public void theFirstRefusalNamesTheRule() {
        // Descriptors before configurations, configurations before class codes
        refused(UsbVerdict.RULE_UNREADABLE, device("d", null, 0x09, 0, 0, 2, hid(0, 0), hid(0, 0)));
        refused(UsbVerdict.RULE_CONFIGURATIONS, device("d", null, 0x09, 0, 0, 2));
        // Interfaces in order, each one's class before its endpoints
        refused(UsbVerdict.RULE_STORAGE, device("d", null, 0, 0, 0, 1,
                iface(0, 0, 0x08, 6, 0x50, ep(0x81, BULK)), iface(1, 0, 0xFF, 0, 0)));
        refused(UsbVerdict.RULE_UNREADABLE, device("d", null, 0, 0, 0, 1,
                iface(0, 0, 0x03, 0, 0, ep(0x81, BULK)), iface(1, 0, 0x08, 6, 0x50)));
        // A refusal after an ask still refuses
        refused(UsbVerdict.RULE_UNLISTED_VENDOR, device("d", null, 0, 0, 0, 1, hid(1, 1), iface(1, 0, 0xFF, 0, 0)));
    }

    @Test
    public void namesAreCleanedBeforeTheyShow() {
        // A right-to-left override would make the name read as something else
        assertEquals("Pad", UsbDeviceSummary.cleanName("‮Pad\u0000\n"));
        assertEquals("DualSense Wireless Controller", UsbDeviceSummary.cleanName("  DualSense Wireless Controller "));
        assertNull(UsbDeviceSummary.cleanName("​\u0007"));
        assertNull(UsbDeviceSummary.cleanName(null));
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longName.append('x');
        }
        assertEquals(UsbDeviceSummary.MAX_NAME, UsbDeviceSummary.cleanName(longName.toString()).length());
        // A character outside the BMP is kept whole or not at all
        assertEquals("Pad 🎮", UsbDeviceSummary.cleanName("Pad 🎮"));
    }
}
