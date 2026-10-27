package com.limelight.binding.usb.share;

import android.content.Context;

import com.limelight.R;

/** What the USB devices page and its messages say, from strings.xml. */
public final class UsbShareText {
    private UsbShareText() {
    }

    /** The device's own name, or its vendor and product IDs */
    public static String name(Context context, UsbDeviceSummary d) {
        return d.name != null ? d.name : context.getString(R.string.usb_share_unnamed, d.vendorId, d.productId);
    }

    /** The line under a device's name: what it's doing, how its last share ended, or its verdict */
    public static String detail(Context context, UsbShareController.Entry e) {
        switch (e.state()) {
            case PERMISSION:
                return context.getString(R.string.usb_share_state_permission);
            case CONFIRM:
                return context.getString(R.string.usb_share_state_confirm);
            case CONNECTING:
                return context.getString(R.string.usb_share_state_connecting);
            case SHARED:
                return context.getString(R.string.usb_share_state_shared);
            case STOPPING:
                return context.getString(R.string.usb_share_state_stopping);
            case LOST:
                return context.getString(R.string.usb_share_state_lost);
            default:
                return e.note() != null ? e.note() : verdict(context, e.verdict());
        }
    }

    public static String verdict(Context context, UsbVerdict v) {
        switch (v.decision) {
            case UsbVerdict.REFUSED:
                return context.getString(R.string.usb_share_verdict_refused, rule(context, v.rule));
            case UsbVerdict.ASK:
                return context.getString(R.string.usb_share_verdict_ask);
            default:
                return context.getString(v.complete ? R.string.usb_share_verdict_allowed : R.string.usb_share_verdict_unchecked);
        }
    }

    /** Why a rule refuses a device */
    public static String rule(Context context, int rule) {
        int[] reasons = {
                R.string.usb_share_rule_hub, R.string.usb_share_rule_storage, R.string.usb_share_rule_network,
                R.string.usb_share_rule_vendor, R.string.usb_share_rule_unconfirmed, R.string.usb_share_rule_configurations,
                R.string.usb_share_rule_unreadable, R.string.usb_share_rule_other_class, R.string.usb_share_rule_mismatch,
                R.string.usb_share_rule_too_fast,
        };
        return context.getString(rule >= 1 && rule <= reasons.length ? reasons[rule - 1] : R.string.usb_share_rule_other);
    }

    /** How a connection the PC, the network or the exporter ended went: the tunnel's own words */
    public static String ended(Context context, UsbShareTunnel.Ending ending) {
        if (ending.messageId == 0) {
            return context.getString(R.string.usb_share_ended);
        }
        if (ending.ruleId != 0) {
            return context.getString(ending.messageId, context.getString(ending.ruleId));
        }
        return context.getString(ending.messageId);
    }
}
