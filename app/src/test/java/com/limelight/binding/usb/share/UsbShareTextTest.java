package com.limelight.binding.usb.share;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import com.limelight.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** What the USB devices page says for each of the contract's rules, and for the tunnel's endings. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class UsbShareTextTest {
    private final Context context = RuntimeEnvironment.getApplication();

    private String str(int id) {
        return context.getString(id);
    }

    @Test
    public void eachRuleHasItsOwnReason() {
        int[] reasons = {
                R.string.usb_share_rule_hub, R.string.usb_share_rule_storage, R.string.usb_share_rule_network,
                R.string.usb_share_rule_vendor, R.string.usb_share_rule_unconfirmed,
                R.string.usb_share_rule_configurations, R.string.usb_share_rule_unreadable,
                R.string.usb_share_rule_other_class, R.string.usb_share_rule_mismatch, R.string.usb_share_rule_too_fast,
        };
        for (int rule = 1; rule <= 10; rule++) {
            assertEquals("rule " + rule, str(reasons[rule - 1]), UsbShareText.rule(context, rule));
        }
        // A rule a later version adds
        assertEquals(str(R.string.usb_share_rule_other), UsbShareText.rule(context, 11));
        assertEquals(str(R.string.usb_share_rule_other), UsbShareText.rule(context, 0));
    }

    // Rule 5 can come from the PC when its check and the phone's disagree, so Starwire may never
    // have asked: the reason says what the rule wants, not what you did
    @Test
    public void ruleFiveDoesntSayYouSaidNo() {
        assertEquals("Keyboards and mice need your yes on this phone first.",
                UsbShareText.rule(context, UsbVerdict.RULE_UNCONFIRMED_INPUT));
    }

    // The tunnel's own words, with its rule's text where it names one, or a plain line without any
    @Test
    public void anEndingIsTheTunnelsOwnWords() {
        assertEquals(str(R.string.usb_share_connect_failed), UsbShareText.ended(context,
                new UsbShareTunnel.Ending(UsbShareTunnel.Ending.CANT_REACH, 0, 0, R.string.usb_share_connect_failed, 0)));
        assertEquals(context.getString(R.string.usb_share_verdict_refused, str(R.string.usb_share_rule_hub)),
                UsbShareText.ended(context, new UsbShareTunnel.Ending(UsbShareTunnel.Ending.NOT_SHARED, 2, 1,
                        R.string.usb_share_verdict_refused, R.string.usb_share_rule_hub)));
        assertEquals(str(R.string.usb_share_ended), UsbShareText.ended(context,
                new UsbShareTunnel.Ending(UsbShareTunnel.Ending.LOST, 0, 0, 0, 0)));
    }
}
