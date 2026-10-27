package com.limelight.binding.usb.share;

import android.content.Context;

import com.limelight.R;

/** What the USB devices page and its messages say, from strings.xml. */
public final class UsbShareText {
    private UsbShareText() {
    }

    /** The device's own name, or its vendor and product IDs */
    public static String name(Context context, UsbDeviceSummary d) {
        return d.name != null ? d.name : context.getString(R.string.usb_unnamed, d.vendorId, d.productId);
    }

    /** The line under a device's name: what it's doing, how its last share ended, or its verdict */
    public static String detail(Context context, UsbShareController.Entry e) {
        switch (e.state()) {
            case PERMISSION:
                return context.getString(R.string.usb_state_permission);
            case CHECKING:
                return context.getString(R.string.usb_state_checking);
            case CONFIRM:
                return context.getString(R.string.usb_state_confirm);
            case CONNECTING:
                return context.getString(R.string.usb_state_connecting);
            case SHARED:
                return context.getString(R.string.usb_state_shared);
            case STOPPING:
                return context.getString(R.string.usb_state_stopping);
            case LOST:
                return context.getString(R.string.usb_state_lost);
            default:
                return e.note() != null ? e.note() : verdict(context, e.verdict());
        }
    }

    public static String verdict(Context context, UsbVerdict v) {
        switch (v.decision) {
            case UsbVerdict.REFUSED:
                return context.getString(R.string.usb_verdict_refused, rule(context, v.rule));
            case UsbVerdict.ASK:
                return context.getString(R.string.usb_verdict_ask);
            default:
                return context.getString(v.complete ? R.string.usb_verdict_allowed : R.string.usb_verdict_unchecked);
        }
    }

    /** Why a rule refuses a device */
    public static String rule(Context context, int rule) {
        int[] reasons = {
                R.string.usb_rule_hub, R.string.usb_rule_storage, R.string.usb_rule_network,
                R.string.usb_rule_vendor, R.string.usb_rule_unconfirmed, R.string.usb_rule_configurations,
                R.string.usb_rule_unreadable, R.string.usb_rule_other_class, R.string.usb_rule_mismatch,
                R.string.usb_rule_too_fast,
        };
        return context.getString(rule >= 1 && rule <= reasons.length ? reasons[rule - 1] : R.string.usb_rule_other);
    }

    /** How a session the PC or the network ended went, for its note */
    public static String closed(Context context, UsbTunnel.Closed why) {
        switch (why.kind) {
            case UsbTunnel.Closed.REFUSED:
                switch (why.code) {
                    case UsbTunnel.REFUSE_NOT_PAIRED:
                        return context.getString(R.string.usb_refuse_not_paired);
                    case UsbTunnel.REFUSE_NO_VERSION:
                        return context.getString(R.string.usb_refuse_no_version);
                    case UsbTunnel.REFUSE_NOT_ALLOWED:
                        return context.getString(R.string.usb_refuse_not_allowed);
                    default:
                        return context.getString(R.string.usb_refuse_malformed);
                }
            case UsbTunnel.Closed.NOT_SHARED:
                switch (why.code) {
                    case UsbTunnel.SHARE_BUSY:
                        return context.getString(R.string.usb_not_shared_busy);
                    case UsbTunnel.SHARE_REFUSED:
                        return context.getString(R.string.usb_not_shared_rules, rule(context, why.detail));
                    case UsbTunnel.SHARE_NO_DRIVER:
                        return context.getString(R.string.usb_not_shared_driver);
                    default:
                        return context.getString(R.string.usb_ended_other);
                }
            case UsbTunnel.Closed.ENDED:
                switch (why.code) {
                    case UsbTunnel.END_STOPPED:
                        return context.getString(R.string.usb_ended_stopped);
                    case UsbTunnel.END_USB_OFF:
                        return context.getString(R.string.usb_ended_usb_off);
                    case UsbTunnel.END_UNPAIRED:
                        return context.getString(R.string.usb_ended_unpaired);
                    case UsbTunnel.END_RULES:
                        return context.getString(R.string.usb_ended_rules, rule(context, why.detail));
                    case UsbTunnel.END_ATTACH_FAILED:
                        return context.getString(R.string.usb_ended_attach);
                    case UsbTunnel.END_CLOSING:
                        return context.getString(R.string.usb_ended_closing);
                    case UsbTunnel.END_PROTOCOL:
                        return context.getString(R.string.usb_ended_protocol);
                    default:
                        return context.getString(R.string.usb_ended_other);
                }
            case UsbTunnel.Closed.LOST:
                return context.getString(R.string.usb_lost);
            default:
                switch (why.code) {
                    case UsbTunnel.FAILED_NO_PAIRING:
                        return context.getString(R.string.usb_failed_no_pairing);
                    case UsbTunnel.FAILED_NOT_HOME:
                        return context.getString(R.string.usb_failed_not_home);
                    case UsbTunnel.FAILED_NOT_PROVEN:
                        return context.getString(R.string.usb_failed_not_proven);
                    case UsbTunnel.FAILED_APP_TOO_OLD:
                        return context.getString(R.string.usb_failed_app_too_old);
                    case UsbTunnel.FAILED_HELPER_TOO_OLD:
                        return context.getString(R.string.usb_failed_helper_too_old);
                    case UsbTunnel.FAILED_EXPORTER:
                        return context.getString(R.string.usb_failed_exporter);
                    default:
                        return context.getString(R.string.usb_failed_no_answer);
                }
        }
    }
}
