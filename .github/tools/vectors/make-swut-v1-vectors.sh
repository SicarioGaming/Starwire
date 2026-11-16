#!/usr/bin/env bash
# Writes swut-v1-vectors.txt (docs/dev/usb-protocol.md, "Test vectors") with openssl and xxd only,
# so the file doesn't come from the app's own codec. The app's JVM test then has to reproduce
# every line from the same fixed inputs.
#
#   bash .github/tools/vectors/make-swut-v1-vectors.sh <output-file> <work-dir>
#
# Needs OpenSSL 3.2 or later (deterministic ECDSA, RFC 6979, for the two certificates), xxd, sha256sum.
# The work dir gets the two test keys from RFC 5903 and scratch files; delete it afterwards.
# check-tools.sh runs this and compares its output with the committed file.
set -eu
export LC_ALL=C MSYS_NO_PATHCONV=1
out=$1
work=$2
# In Git Bash, openssl is a Windows program and MSYS_NO_PATHCONV keeps a /tmp path as it is, so it
# gets the work dir as a Windows path
if command -v cygpath > /dev/null 2>&1; then
    work=$(cygpath -m "$work")
fi
mkdir -p "$work"

lower() { tr 'A-F' 'a-f'; }
hex() { xxd -p -c 100000 "$1" | tr -d '\r\n'; }
unhex() { printf '%s' "$1" | xxd -r -p > "$2"; }
ascii() { printf '%s' "$1" | xxd -p -c 100000 | tr -d '\r\n'; }
# HKDF-SHA256 (RFC 5869): salt ikm info [length], all hex
hkdf() { openssl kdf -keylen "${4:-32}" -kdfopt digest:SHA256 -kdfopt "hexsalt:$1" -kdfopt "hexkey:$2" -kdfopt "hexinfo:$3" HKDF | tr -d ':\r\n' | lower; }
# HMAC-SHA256 of hex data under a hex key; MAC16 is its first 16 bytes
hmac() { unhex "$2" "$work/mac-in"; openssl dgst -sha256 -mac HMAC -macopt "hexkey:$1" -binary "$work/mac-in" > "$work/mac-out"; hex "$work/mac-out"; }
mac16() { hmac "$1" "$2" | cut -c1-32; }
sha256() { unhex "$1" "$work/sha-in"; sha256sum "$work/sha-in" | cut -c1-64; }
u16le() { printf '%02x%02x' $(( $1 & 255 )) $(( ($1 >> 8) & 255 )); }
frame() { printf '%02x00%s%s' "$1" "$(u16le $(( ${#2} / 2 )))" "$2"; }   # type, payload in hex

ZERO32=0000000000000000000000000000000000000000000000000000000000000000

# ---- fixed inputs (the table in usb-protocol.md)
PAIR_KEY=000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f
DEVICE_ID=a0a1a2a3a4a5a6a7
HELPER_NONCE=d0d1d2d3d4d5d6d7d8d9dadbdcdddedf
PHONE_NONCE=e0e1e2e3e4e5e6e7e8e9eaebecedeeef
OTHER_PHONE_NONCE=f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff
# RFC 5903 section 8.1: i is the phone, r the helper
I_D=c88f01f510d9ac3f70a292daa2316de544e9aab8afe84049c62a9c57862d1433
I_X=dad0b65394221cf9b051e1feca5787d098dfe637fc90b9ef945d0c3772581180
I_Y=5271a0461cdb8252d61f1c456fa3e59ab1f45b33accf5f58389e0577b8990bb3
R_D=c6ef9c5d78ae012a011164acb397ce2088685d8f06bf9be0b283ab46476bee53
R_X=d12dfb5289c8d4f81208b70270398c342296970a0bccb74c736fc7554494bf63
R_Y=56fbf3ca366cc23e8157854c13c58d6aac23f046ada30f8353e74f33039872ab

# ---- the two certificates: self-signed P-256, CN=Starwire, no extensions, fixed dates and serials
cat > "$work/req.cnf" <<'EOF'
[req]
distinguished_name = dn
prompt = no
[dn]
CN = Starwire
EOF
cert() {   # name d x y serial
  # ECPrivateKey (RFC 5915): version 1, d, the P-256 OID, then 04 || X || Y
  unhex "30770201010420$2a00a06082a8648ce3d030107a14403420004$3$4" "$work/$1.key.der"
  openssl req -x509 -new -key "$work/$1.key.der" -keyform DER -set_serial "$5" \
      -not_before 20260101000000Z -not_after 20360101000000Z -sha256 -sigopt nonce-type:1 \
      -config "$work/req.cnf" -addext subjectKeyIdentifier=none -addext authorityKeyIdentifier=none \
      -outform DER -out "$work/$1.cert.der"
  hex "$work/$1.cert.der"
}
CERT_PHONE=$(cert phone $I_D $I_X $I_Y 1)
CERT_HELPER=$(cert helper $R_D $R_X $R_Y 2)
FP_PHONE=$(sha256 "$CERT_PHONE")
FP_HELPER=$(sha256 "$CERT_HELPER")

# ---- keys
LABEL_USB_AUTH=$(ascii "starwire-usb/1 auth")
LABEL_MIC_AUTH=$(ascii "starwire-mic/1 auth")
LABEL_BIND=$(ascii "starwire-usb/1 bind")
LABEL_BIND_ACK=$(ascii "starwire-usb/1 bind ack")
K_USB=$(hkdf $ZERO32 $PAIR_KEY "$LABEL_USB_AUTH$DEVICE_ID")
K_MIC_AUTH=$(hkdf $ZERO32 $PAIR_KEY "$LABEL_MIC_AUTH$DEVICE_ID")

# ---- the handshake
hello() { frame 1 "$(ascii SWUT)$(printf '%02x%02x' "$1" "$2")0000$HELPER_NONCE"; }   # lowest highest
HELLO=$(hello 1 1)
HELLO_1_TO_2=$(hello 1 2)
bind() {   # key hello first-fingerprint second-fingerprint phone-nonce
  local body="02002c0001000000$DEVICE_ID$5"
  printf '%s%s' "$body" "$(mac16 "$1" "$LABEL_BIND$2$3$4$body")"
}
BIND=$(bind $K_USB $HELLO $FP_PHONE $FP_HELPER $PHONE_NONCE)
bind_ack() { printf '03001000%s' "$(mac16 "$1" "${LABEL_BIND_ACK}$2$FP_PHONE$FP_HELPER${3}03001000")"; }   # key hello bind
BIND_ACK=$(bind_ack $K_USB $HELLO $BIND)
OTHER_BIND=$(bind $K_USB $HELLO $FP_PHONE $FP_HELPER $OTHER_PHONE_NONCE)

# ---- SHARE: a pad with one HID interface, 1209:0001, bus ID 1-1
DEV=120100020000004009120100000101020001
REPORT=05010905a101150025017501951005091901291081020501150026ff0075089502093009318102c0
REPORT_LEN=$(( ${#REPORT} / 2 ))
IFACE=090400000103000000
HIDD="09211101000122$(u16le $REPORT_LEN)"
EP=07058103400004
CONFIG_LEN=$(( 9 + (${#IFACE} + ${#HIDD} + ${#EP}) / 2 ))
CONFIG="0902$(u16le $CONFIG_LEN)0101008032$IFACE$HIDD$EP"
NAME=$(ascii "Test pad")
BUSID=$(ascii "1-1")
SHARE=$(frame 5 "00$(printf '%02x' $(( ${#BUSID} / 2 )))$BUSID$(printf '%02x' $(( ${#NAME} / 2 )))$NAME$DEV$(u16le $CONFIG_LEN)${CONFIG}0100$(u16le $REPORT_LEN)$REPORT")

# OP_REQ_IMPORT for bus ID 1-1 (USB/IP's own numbers are big-endian): version 0x0111, code 0x8003,
# status 0, then the bus ID padded with zeros to 32 bytes
IMPORT="0111800300000000$BUSID$(printf '%058d' 0)"

{
  echo "kat.hkdf_rfc5869_1=$(hkdf 000102030405060708090a0b0c 0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b f0f1f2f3f4f5f6f7f8f9 42)"
  echo "kat.mac16_rfc4231_5=$(mac16 0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c "$(ascii "Test With Truncation")")"
  echo "key.usb=$K_USB"
  echo "cert.phone=$CERT_PHONE"
  echo "cert.helper=$CERT_HELPER"
  echo "fp.phone=$FP_PHONE"
  echo "fp.helper=$FP_HELPER"
  echo "frame.hello=$HELLO"
  echo "frame.bind=$BIND"
  echo "frame.bind_ack=$BIND_ACK"
  echo "frame.refuse_not_paired=$(frame 4 01000000)"
  echo "frame.share=$SHARE"
  echo "frame.share_ack=$(frame 6 00000000)"
  echo "frame.share_ack_unconfirmed_keyboard=$(frame 6 02050000)"
  echo "frame.data_import=$(frame 7 "$IMPORT")"
  echo "frame.keepalive=$(frame 8 "")"
  echo "frame.end_stopped=$(frame 9 01000000)"
  echo "refuse.bind_mic_key=$(bind $K_MIC_AUTH $HELLO $FP_PHONE $FP_HELPER $PHONE_NONCE)"
  echo "refuse.bind_swapped_fingerprints=$(bind $K_USB $HELLO $FP_HELPER $FP_PHONE $PHONE_NONCE)"
  echo "refuse.bind_other_hello=$(bind $K_USB $HELLO_1_TO_2 $FP_PHONE $FP_HELPER $PHONE_NONCE)"
  echo "refuse.bind_ack_other_bind=$(bind_ack $K_USB $HELLO $OTHER_BIND)"
} > "$out"
