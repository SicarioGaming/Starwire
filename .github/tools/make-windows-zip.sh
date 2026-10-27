#!/usr/bin/env bash
# Makes the release's Windows download, Starwire-Microphone-for-Windows.zip: the microphone
# receiver and the USB helper, each with its setup and its guide, at the top of the zip. The
# Release workflow publishes it, and the Build workflow makes it on every push, so a file missing
# from it shows up long before a tag.
#
#   bash .github/tools/make-windows-zip.sh OUT-DIR    makes OUT-DIR/Starwire-Microphone-for-Windows.zip
#   bash .github/tools/make-windows-zip.sh --check    checks the files only, and makes no zip
#   bash .github/tools/make-windows-zip.sh --list     prints the files that go in, one a line
#
# The files come from windows/ in this repo, or from the folder WINDOWS_DIR names (the tests use
# it). It stops, with no zip, if a file on the list is missing or empty, if setup-microphone.ps1,
# setup-usb.ps1 or their .cmd files take a file from their folder that isn't on the list, if it
# can't find the list of sources setup-usb.ps1 builds the helper from, if a step fails, or if the
# zip it made doesn't hold exactly the list.
# Exit 0 = made (or checked), 1 = a file is missing or the zip is wrong, 2 = usage error.
#
# Needs bash, grep and sed, and zip and unzip to make the zip (not for --check or --list).

set -euo pipefail
shopt -s inherit_errexit 2>/dev/null || true
export LC_ALL=C

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC=${WINDOWS_DIR:-$(cd "$HERE/../.." && pwd)/windows}
ZIP_NAME=Starwire-Microphone-for-Windows.zip

# The microphone receiver: what 0.2.0 and 0.2.1 shipped
MIC_FILES="setup-microphone.cmd setup-microphone.ps1 StarwireMicReceiver.cs SecureMic.cs starwire-mic.ico README.md"
# The USB helper: its setup, the sources setup-usb.ps1 builds it from, and its guide. It reads no
# other file next to it: the test vectors and the device rules' cases are only for its tests.
USB_FILES="setup-usb.cmd setup-usb.ps1 StarwireUsbHelper.cs UsbTunnel.cs UsbRelay.cs UsbTunnelCodec.cs UsbDeviceRules.cs USB-HELPER.md"
FILES="$MIC_FILES $USB_FILES"

usage() {
  echo "usage: make-windows-zip.sh OUT-DIR | --check | --list" >&2
  exit 2
}

case "${1:-}" in
  --list) [ $# -eq 1 ] || usage; printf '%s\n' $FILES; exit 0 ;;
  --check) [ $# -eq 1 ] || usage; out="" ;;
  ""|-*) usage ;;
  *) [ $# -eq 1 ] || usage; out=$1 ;;
esac

# A step that fails stops it, and a zip it didn't finish is removed
zip="" made="" said=""
on_exit() {
  local status=$?
  if [ -n "$zip" ] && [ -z "$made" ]; then rm -f "$zip"; fi
  if [ $status -ne 0 ] && [ -z "$said" ]; then
    echo "make-windows-zip.sh: a step failed (see above), so there's no zip" >&2
  fi
}
trap on_exit EXIT

problems=0
problem() { echo "make-windows-zip.sh: $*" >&2; problems=$((problems + 1)); }
on_list() { case " $FILES " in *" $1 "*) return 0 ;; esac; return 1; }

# What a setup file takes from its own folder, one name a line: "%~dp0<name>" in a .cmd file,
# Join-Path $here '<name>' in a .ps1 file, and the sources setup-usb.ps1 builds the helper from.
# (grep's exit 1 is "no match"; 2 is an error.)
takes() { # file
  { grep -o '%~dp0[A-Za-z0-9._-]*' "$1" || [ $? -eq 1 ]; } | sed 's/^%~dp0//'
  { grep -o "Join-Path \$here '[^']*'" "$1" || [ $? -eq 1 ]; } | sed "s/^Join-Path \$here '//; s/'\$//"
  usb_sources "$1"
}
usb_sources() { # file: the names on its $sourceNames = @(...) line
  { grep '^\$sourceNames = @(' "$1" || [ $? -eq 1 ]; } | { grep -o "'[^']*'" || [ $? -eq 1 ]; } | tr -d "'"
}

if [ ! -d "$SRC" ]; then
  said=yes
  echo "make-windows-zip.sh: no folder $SRC" >&2
  exit 1
fi
for f in $FILES; do
  [ -s "$SRC/$f" ] || problem "$f is missing from $SRC, or empty"
done
for setup in setup-microphone.cmd setup-microphone.ps1 setup-usb.cmd setup-usb.ps1; do
  [ -s "$SRC/$setup" ] || continue
  names=$(takes "$SRC/$setup")
  [ -n "$names" ] || problem "can't find the files $setup takes from its folder"
  for name in $names; do
    on_list "$name" || problem "$setup takes $name from its folder, but it isn't on the zip's list"
  done
done
if [ -s "$SRC/setup-usb.ps1" ]; then
  sources=$(usb_sources "$SRC/setup-usb.ps1")
  [ -n "$sources" ] || problem "can't find the sources setup-usb.ps1 builds the helper from (\$sourceNames = @(...))"
fi
if [ $problems -gt 0 ]; then
  said=yes
  echo "make-windows-zip.sh: $problems problem(s), so there's no zip" >&2
  exit 1
fi
count=$(printf '%s\n' $FILES | wc -l)
count=${count//[[:space:]]/}
if [ -z "$out" ]; then
  echo "the Windows zip's $count files are in $SRC, with every file the setups take"
  exit 0
fi

# Made from scratch each time: zip adds to a zip that's already there
mkdir -p "$out"
zip=$(cd "$out" && pwd)/$ZIP_NAME
rm -f "$zip"
(cd "$SRC" && zip -q -X "$zip" $FILES)
got=$(unzip -Z1 "$zip" | sort)
want=$(printf '%s\n' $FILES | sort)
if [ "$got" != "$want" ]; then
  said=yes
  echo "make-windows-zip.sh: the zip holds $(echo $got), not the list: $(echo $want)" >&2
  exit 1
fi
made=yes
echo "made $zip, with $count files"
