#!/usr/bin/env bash
# Writes swut-v1-policy.txt, the device rules' shared cases (docs/dev/usb-protocol.md, "The device
# rules, version 1" and "Test vectors"), from descriptors written out by hand here, apart from the
# app's code. Each case is two lines: <case>.share=<a whole SHARE frame in hex> and
# <case>.want=<allow, ask, or refuse and the rule>. Starwire's device rules and the helper's must
# each reach every decision.
#
#   bash make-swut-v1-policy.sh <output-file>
#
# Needs only bash. check-tools.sh runs it and compares its output with the committed file.
set -eu
export LC_ALL=C
out=$1

u16le() { printf '%02x%02x' $(( $1 & 255 )) $(( ($1 >> 8) & 255 )); }
bytes() { printf '%s' $(( ${#1} / 2 )); }
ascii() { local s=$1 i; for (( i = 0; i < ${#s}; i++ )); do printf '%02x' "'${s:i:1}"; done; }

# The device descriptor: USB 2.0, ep0 64 bytes, 1209:0001, version 1.00, strings 1 and 2, no
# serial. Arguments: class, subclass, protocol, and the number of configurations (default 1).
dev() { printf '12010002%s%s%s40091201000001010200%s' "$1" "$2" "$3" "${4:-01}"; }
DEV=$(dev 00 00 00)

# A configuration descriptor around its body: config <bNumInterfaces> <body>
config() { printf '0902%s%s01008032%s' "$(u16le $(( 9 + ${#2} / 2 )))" "$1" "$2"; }
iface() { printf '0904%s%s%s%s%s%s00' "$1" "$2" "$3" "$4" "$5" "$6"; }   # number alt endpoints class subclass protocol
ep() { printf '0705%s%s%s%s' "$1" "$2" "$(u16le "$3")" "$4"; }          # address attributes maxPacket interval
ep9() { printf '0905%s%s%s%s0000' "$1" "$2" "$(u16le "$3")" "$4"; }     # an audio endpoint, two bytes longer
iad() { printf '080b%s%s%s%s%s00' "$1" "$2" "$3" "$4" "$5"; }           # first count class subclass protocol
hidd() { printf '09211101000122%s' "$(u16le "$1")"; }                    # a HID descriptor for a report of this length

# A SHARE frame: share <device> <configuration> [<interface>:<report> ...], bus id 1-1, "Test pad"
share() {
    local d=$1 c=$2 body n=0 reports=""
    shift 2
    for r in "$@"; do
        local number=${r%%:*} rep=${r#*:}
        reports="$reports$number$(u16le $(bytes "$rep"))$rep"
        n=$((n + 1))
    done
    body="00$(printf '%02x' 3)$(ascii 1-1)$(printf '%02x' 8)$(ascii 'Test pad')$d$(u16le $(bytes "$c"))$c$(printf '%02x' $n)$reports"
    printf '05%s%s%s' 00 "$(u16le $(bytes "$body"))" "$body"
}

# ---- report descriptors
# Inside a pad's collection: 16 buttons, then X and Y
PAD_ITEMS=150025017501951005091901291081020501150026ff0075089502093009318102
PAD=05010905a101${PAD_ITEMS}c0
# Inside other collections: 8 one-bit inputs
ITEMS=15002501750195088102
KEYBOARD=05010906a101050719e029e715002501750195088102c0
CONSUMER=050c0901a101150025017501950209e909ea810295068103c0

# ---- interfaces
hid_pad() { printf '%s%s%s' "$(iface 00 00 01 03 00 00)" "$(hidd "$(bytes "$1")")" "$(ep 81 03 64 04)"; }
pad_config() { config 01 "$(hid_pad "$1")"; }
PAD_CONFIG=$(pad_config "$PAD")
bare() { config 01 "$(iface 00 00 00 "$1" "$2" "$3")"; }   # one interface of this class, no endpoints

case_() { echo "$1.share=$2"; echo "$1.want=$3"; }

{
# The pad all the others change one thing in
case_ pad "$(share "$DEV" "$PAD_CONFIG" "00:$PAD")" allow

# The device's own class codes: only 00/00/00 and EF/02/01 leave the choice to the interfaces
case_ device_class_iad "$(share "$(dev ef 02 01)" "$PAD_CONFIG" "00:$PAD")" allow
case_ device_class_storage "$(share "$(dev 08 06 50)" "$PAD_CONFIG" "00:$PAD")" "refuse 2"
case_ device_class_hub "$(share "$(dev 09 00 00)" "$PAD_CONFIG" "00:$PAD")" "refuse 1"
case_ device_class_comms "$(share "$(dev 02 00 00)" "$PAD_CONFIG" "00:$PAD")" "refuse 3"
case_ device_class_vendor "$(share "$(dev ff ff ff)" "$PAD_CONFIG" "00:$PAD")" "refuse 4"
case_ device_class_hid "$(share "$(dev 03 00 00)" "$PAD_CONFIG" "00:$PAD")" "refuse 8"
case_ device_class_misc_other "$(share "$(dev ef 01 01)" "$PAD_CONFIG" "00:$PAD")" "refuse 8"
case_ two_configurations "$(share "$(dev 00 00 00 02)" "$PAD_CONFIG" "00:$PAD")" "refuse 6"

# Interfaces by class
case_ interface_storage "$(share "$DEV" "$(config 01 "$(iface 00 00 02 08 06 50)$(ep 81 02 512 00)$(ep 02 02 512 00)")")" "refuse 2"
case_ interface_still_image "$(share "$DEV" "$(bare 06 01 01)")" "refuse 2"
case_ interface_hub "$(share "$DEV" "$(bare 09 00 00)")" "refuse 1"
case_ interface_cdc "$(share "$DEV" "$(bare 02 02 01)")" "refuse 3"
case_ interface_cdc_data "$(share "$DEV" "$(bare 0a 00 00)")" "refuse 3"
case_ interface_wireless_rndis "$(share "$DEV" "$(bare e0 01 03)")" "refuse 3"
case_ interface_misc_rndis "$(share "$DEV" "$(bare ef 04 01)")" "refuse 3"
case_ interface_vendor "$(share "$DEV" "$(bare ff 5d 01)")" "refuse 4"
case_ interface_firmware_update "$(share "$DEV" "$(bare fe 01 02)")" "refuse 8"
case_ interface_video "$(share "$DEV" "$(bare 0e 01 00)")" "refuse 8"
case_ interface_audio_no_subclass "$(share "$DEV" "$(bare 01 00 00)")" "refuse 8"
case_ hid_boot_keyboard "$(share "$DEV" "$(config 01 "$(iface 00 00 01 03 01 01)$(hidd 40)$(ep 81 03 64 04)")" "00:$PAD")" ask

# Endpoints that don't fit the class
case_ hid_bulk_endpoint "$(share "$DEV" "$(config 01 "$(iface 00 00 01 03 00 00)$(hidd 40)$(ep 81 02 64 00)")" "00:$PAD")" "refuse 7"
case_ hid_two_in_endpoints "$(share "$DEV" "$(config 01 "$(iface 00 00 02 03 00 00)$(hidd 40)$(ep 81 03 64 04)$(ep 82 03 64 04)")" "00:$PAD")" "refuse 7"
case_ audio_streaming_bulk "$(share "$DEV" "$(config 01 "$(iface 00 00 01 01 02 00)$(ep 01 02 64 00)")")" "refuse 7"
case_ midi "$(share "$DEV" "$(config 01 "$(iface 00 00 02 01 03 00)$(ep 01 02 64 00)$(ep 81 02 64 00)")")" allow

# Interface association descriptors: the function's class counts too
case_ iad_storage_function "$(share "$(dev ef 02 01)" "$(config 01 "$(iad 00 01 08 06 50)$(hid_pad "$PAD")")" "00:$PAD")" "refuse 2"
case_ iad_class_mismatch "$(share "$(dev ef 02 01)" "$(config 01 "$(iad 00 01 01 00 20)$(hid_pad "$PAD")")" "00:$PAD")" "refuse 7"
case_ iad_missing_interface "$(share "$(dev ef 02 01)" "$(config 01 "$(iad 00 02 03 00 00)$(hid_pad "$PAD")")" "00:$PAD")" "refuse 7"

# A headset: audio control, audio streaming (an empty and a working setting), and HID buttons on
# the Consumer page, which an audio device may have
HEADSET=$(config 03 "$(iface 00 00 00 01 01 00)092401000109000101$(iface 01 00 00 01 02 00)$(iface 01 01 01 01 02 00)$(ep9 01 09 192 01)$(iface 02 00 01 03 00 00)$(hidd "$(bytes "$CONSUMER")")$(ep 83 03 4 08)")
case_ headset "$(share "$DEV" "$HEADSET" "02:$CONSUMER")" allow

# Descriptors that don't read
case_ hid_no_report "$(share "$DEV" "$PAD_CONFIG")" "refuse 7"
case_ hid_report_length_mismatch "$(share "$DEV" "$(config 01 "$(iface 00 00 01 03 00 00)$(hidd 41)$(ep 81 03 64 04)")" "00:$PAD")" "refuse 7"
case_ report_for_missing_interface "$(share "$DEV" "$PAD_CONFIG" "00:$PAD" "01:$PAD")" "refuse 7"
case_ endpoint_count_mismatch "$(share "$DEV" "$(config 01 "$(iface 00 00 02 03 00 00)$(hidd 40)$(ep 81 03 64 04)")" "00:$PAD")" "refuse 7"
case_ endpoint_zero "$(share "$DEV" "$(config 01 "$(iface 00 00 01 03 00 00)$(hidd 40)$(ep 80 03 64 04)")" "00:$PAD")" "refuse 7"
case_ interface_count_mismatch "$(share "$DEV" "$(config 02 "$(hid_pad "$PAD")")" "00:$PAD")" "refuse 7"
case_ descriptor_zero_length "$(share "$DEV" "$(config 01 "$(hid_pad "$PAD")0000")" "00:$PAD")" "refuse 7"
case_ descriptor_overrun "$(share "$DEV" "$(config 01 "$(iface 00 00 01 03 00 00)$(hidd 40)08058103400004")" "00:$PAD")" "refuse 7"

# Report descriptors: what the top-level collections are
report() { share "$DEV" "$(pad_config "$1")" "00:$1"; }
case_ report_keyboard "$(report "$KEYBOARD")" ask
case_ report_pad_and_keyboard "$(report "$PAD$KEYBOARD")" ask
# Game Controls in effect, but a 4-byte Usage carries its own page: Generic Desktop, Keyboard
case_ report_extended_usage_keyboard "$(report "05050b06000100a101050719e029e715002501750195088102c0")" ask
# The Keyboard page in effect, but the 4-byte Usage says Generic Desktop, Game Pad
case_ report_extended_usage_pad "$(report "05070b05000100a101${PAD_ITEMS}c0")" allow
case_ report_simulation "$(report "05020902a101${PAD_ITEMS}c0")" allow
case_ report_vendor_page "$(report "0600ff0901a101${ITEMS}c0")" allow
case_ report_consumer_without_audio "$(report "$CONSUMER")" ask
case_ report_digitizer "$(report "050d0904a101${ITEMS}c0")" ask
case_ report_system_control "$(report "05010980a101${ITEMS}c0")" ask
case_ report_led_page "$(report "05080901a101${ITEMS}c0")" "refuse 8"

# Report descriptors that every parser must read the same way, or refuse
case_ report_page_after_usage "$(report "09050501a101${PAD_ITEMS}c0")" "refuse 7"
case_ report_two_usages "$(report "050109050906a101${PAD_ITEMS}c0")" "refuse 7"
case_ report_usage_range "$(report "050119042905a101${PAD_ITEMS}c0")" "refuse 7"
case_ report_delimiter "$(report "0501a90109050906a900a101${PAD_ITEMS}c0")" "refuse 7"
case_ report_push_pop "$(report "0501a40905a101${PAD_ITEMS}c0b4")" "refuse 7"
case_ report_long_item "$(report "fe0000$PAD")" "refuse 7"
case_ report_reserved_tag "$(report "c500$PAD")" "refuse 7"
case_ report_wide_usage_page "$(report "07010001000905a101${PAD_ITEMS}c0")" "refuse 7"
case_ report_physical_top "$(report "05010905a100${PAD_ITEMS}c0")" "refuse 7"
case_ report_input_outside "$(report "750195018103$PAD")" "refuse 7"
case_ report_unclosed "$(report "05010905a101${PAD_ITEMS}")" "refuse 7"
case_ report_stray_end "$(report "${PAD}c0")" "refuse 7"
case_ report_no_collection "$(report "05010905")" "refuse 7"
case_ report_cut_item "$(report "${PAD}26ff")" "refuse 7"
case_ report_too_deep "$(report "05010905a101$(printf 'a100%.0s' $(seq 16))${ITEMS}$(printf 'c0%.0s' $(seq 17))")" "refuse 7"
case_ report_deepest "$(report "05010905a101$(printf 'a100%.0s' $(seq 15))${ITEMS}$(printf 'c0%.0s' $(seq 16))")" allow

# The cases below came after a review of Starwire's rules: each check above them that no case
# tested before. Mice, pointers and keypads ask, like keyboards
case_ report_mouse "$(report "05010902a101${ITEMS}c0")" ask
case_ report_pointer "$(report "05010901a101${ITEMS}c0")" ask
case_ report_keypad "$(report "05010907a101${ITEMS}c0")" ask
case_ hid_boot_mouse "$(share "$DEV" "$(config 01 "$(iface 00 00 01 03 01 02)$(hidd 40)$(ep 81 03 64 04)")" "00:$PAD")" ask
# Only subclass 1 with protocol 1 or 2 is a boot keyboard or mouse
case_ hid_boot_other_protocol "$(share "$DEV" "$(config 01 "$(iface 00 00 01 03 01 00)$(hidd 40)$(ep 81 03 64 04)")" "00:$PAD")" allow
case_ hid_protocol_without_boot "$(share "$DEV" "$(config 01 "$(iface 00 00 01 03 00 01)$(hidd 40)$(ep 81 03 64 04)")" "00:$PAD")" allow

# The other pads, and the pages and usages that aren't one
case_ report_joystick "$(report "05010904a101${PAD_ITEMS}c0")" allow
case_ report_multi_axis "$(report "05010908a101${PAD_ITEMS}c0")" allow
case_ report_game_controls "$(report "05050901a101${PAD_ITEMS}c0")" allow
case_ report_wireless_radio "$(report "0501090ca101${ITEMS}c0")" "refuse 8"
case_ report_fido_page "$(report "06d0f10901a101${ITEMS}c0")" "refuse 8"
case_ report_page_below_vendor "$(report "06fffe0901a101${ITEMS}c0")" "refuse 8"
TELEPHONY=050b0905a101${ITEMS}c0
case_ report_telephony_without_audio "$(report "$TELEPHONY")" ask
headset_with() { config 03 "$(iface 00 00 00 01 01 00)092401000109000101$(iface 01 00 00 01 02 00)$(iface 01 01 01 01 02 00)$(ep9 01 09 192 01)$(iface 02 00 01 03 00 00)$(hidd "$(bytes "$1")")$(ep 83 03 4 08)"; }
case_ headset_telephony "$(share "$DEV" "$(headset_with "$TELEPHONY")" "02:$TELEPHONY")" allow

# Endpoints that do and don't fit their class
case_ hid_in_and_out "$(share "$DEV" "$(config 01 "$(iface 00 00 02 03 00 00)$(hidd 40)$(ep 81 03 64 04)$(ep 01 03 64 04)")" "00:$PAD")" allow
case_ hid_two_out_endpoints "$(share "$DEV" "$(config 01 "$(iface 00 00 03 03 00 00)$(hidd 40)$(ep 81 03 64 04)$(ep 01 03 64 04)$(ep 02 03 64 04)")" "00:$PAD")" "refuse 7"
case_ hid_isochronous_endpoint "$(share "$DEV" "$(config 01 "$(iface 00 00 01 03 00 00)$(hidd 40)$(ep 81 01 64 01)")" "00:$PAD")" "refuse 7"
case_ audio_control_interrupt_in "$(share "$DEV" "$(config 01 "$(iface 00 00 01 01 01 00)$(ep 81 03 16 08)")")" allow
case_ audio_control_two_endpoints "$(share "$DEV" "$(config 01 "$(iface 00 00 02 01 01 00)$(ep 81 03 16 08)$(ep 82 03 16 08)")")" "refuse 7"
case_ audio_control_bulk_endpoint "$(share "$DEV" "$(config 01 "$(iface 00 00 01 01 01 00)$(ep 81 02 64 00)")")" "refuse 7"
case_ audio_control_out_endpoint "$(share "$DEV" "$(config 01 "$(iface 00 00 01 01 01 00)$(ep 01 03 16 08)")")" "refuse 7"
case_ midi_interrupt_endpoint "$(share "$DEV" "$(config 01 "$(iface 00 00 01 01 03 00)$(ep 81 03 64 04)")")" "refuse 7"

# Configurations that don't read
case_ endpoint_twice "$(share "$DEV" "$(config 01 "$(iface 00 00 02 01 03 00)$(ep 01 02 64 00)$(ep 01 02 64 00)")")" "refuse 7"
case_ interface_twice "$(share "$DEV" "$(config 01 "$(iface 00 00 00 01 01 00)$(iface 00 00 00 01 01 00)")")" "refuse 7"
case_ endpoint_nine_bytes_outside_audio "$(share "$DEV" "$(config 01 "$(iface 00 00 01 03 00 00)$(hidd 40)$(ep9 81 03 64 04)")" "00:$PAD")" "refuse 7"
case_ interface_ten_bytes "$(share "$DEV" "$(config 01 0a040000000101000000)")" "refuse 7"
case_ iad_nine_bytes "$(share "$(dev ef 02 01)" "$(config 01 "090b00010300000000$(hid_pad "$PAD")")" "00:$PAD")" "refuse 7"
case_ iad_no_interfaces "$(share "$(dev ef 02 01)" "$(config 01 "$(iad 00 00 03 00 00)$(hid_pad "$PAD")")" "00:$PAD")" "refuse 7"
case_ iad_named_twice "$(share "$(dev ef 02 01)" "$(config 01 "$(iad 00 01 03 00 00)$(iad 00 01 03 00 00)$(hid_pad "$PAD")")" "00:$PAD")" "refuse 7"
case_ configuration_inside "$(share "$DEV" "$(config 01 "$(hid_pad "$PAD")090209000101008032")" "00:$PAD")" "refuse 7"
case_ device_descriptor_inside "$(share "$DEV" "$(config 01 "$(hid_pad "$PAD")$DEV")" "00:$PAD")" "refuse 7"
case_ hid_descriptor_physical_first "$(share "$DEV" "$(config 01 "$(iface 00 00 01 03 00 00)092111010001232800$(ep 81 03 64 04)")" "00:$PAD")" "refuse 7"

# Class codes: EF/02 groups by IADs only with protocol 1, and an IAD's video function is rule 8
case_ device_class_misc_other_protocol "$(share "$(dev ef 02 02)" "$PAD_CONFIG" "00:$PAD")" "refuse 8"
case_ iad_video_function "$(share "$(dev ef 02 01)" "$(config 01 "$(iad 00 01 0e 03 00)$(hid_pad "$PAD")")" "00:$PAD")" "refuse 8"

# Report descriptors: reserved items and tags, the Delimiter alone, and a Collection's data that
# isn't 1
case_ report_reserved_type "$(report "0c$PAD")" "refuse 7"
case_ report_reserved_main_tag "$(report "d0$PAD")" "refuse 7"
case_ report_reserved_local_tag "$(report "68$PAD")" "refuse 7"
case_ report_delimiter_alone "$(report "0501a9010905a900a101${PAD_ITEMS}c0")" "refuse 7"
case_ report_collection_two_bytes "$(report "05010905a20101${PAD_ITEMS}c0")" "refuse 7"
# A 4-byte Usage carries its page, so a Usage Page after it changes nothing
case_ report_extended_usage_then_page "$(report "0b050001000507a101${PAD_ITEMS}c0")" allow
} > "$out"
