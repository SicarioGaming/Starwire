#!/usr/bin/env bash
# Sets up this PC to receive Starwire's microphone, whatever streaming host it runs (GPL-3.0, part
# of Starwire).
#
# - Builds Starwire's microphone receiver (StarwireMicReceiver.cs and the files next to it, with
#   ../windows/SecureMic.cs) with the .NET SDK, and installs it in ~/.local/lib/starwire-mic, with
#   the command starwire-mic in ~/.local/bin.
# - Runs it as a systemd user service, now and whenever you sign in. It makes a microphone called
#   "Starwire microphone" that every app can use, through PipeWire: there's no driver to install.
# - Lets it through the firewall (ufw or firewalld) on UDP port 6980, for local-network and private
#   VPN addresses only. This step asks for your password (sudo).
# - Checks the microphone works.
# - Builds and installs the Starwire microphone app (app/): a window and a tray icon for pairing, the
#   paired devices, the mic check and the log. It's in the app menu, and its tray icon starts when
#   you sign in. Building it downloads Avalonia from NuGet, checked against app/packages.lock.json.
#
# Then choose "Starwire microphone" as the microphone in Discord or the game, and pair Starwire with
# the PC: open the Starwire microphone app and choose Pair a device (or run starwire-mic pair), and
# type the code it shows into Starwire (Settings > Sound & vibration > Send my microphone > Pair with
# the PC). Only paired devices can send audio to the receiver, and it's encrypted on the way.
#
#   linux/setup-microphone.sh [--port 6980] [--no-startup] [--no-firewall] [--no-app]
#                             [--allow-unencrypted [--name Stream1]] [--uninstall]
#
# --port               must match Starwire's microphone port. Default 6980.
# --no-startup         don't start the receiver when you sign in (it's started once now).
# --no-firewall        leave the firewall alone.
# --no-app             only the receiver and the starwire-mic command, without the app.
# --allow-unencrypted  also plays plain, unencrypted VBAN (from Starwire 0.2.0 or another VBAN app)
#                      named --name. Not safe: anyone on the same network can then send sound into
#                      this PC's microphone or listen to it. Leave it off unless you need it.
# --uninstall          removes the receiver, its service, the app, its firewall rules and the list
#                      of paired devices.
#
# Run it as yourself, not with sudo: the receiver runs as you, and the paired devices are yours.

set -euo pipefail

port=6980
stream_name=Stream1
allow_unencrypted=0
startup=1
firewall=1
with_app=1
uninstall=0
while [ $# -gt 0 ]; do
    case "$1" in
        --port) port=${2:?--port needs a number}; shift ;;
        --name) stream_name=${2:?--name needs a stream name}; shift ;;
        --allow-unencrypted) allow_unencrypted=1 ;;
        --no-startup) startup=0 ;;
        --no-firewall) firewall=0 ;;
        --no-app) with_app=0 ;;
        --uninstall) uninstall=1 ;;
        -h|--help) sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown option $1 (see --help)"; exit 2 ;;
    esac
    shift
done
if ! [[ "$port" =~ ^[0-9]+$ ]] || [ "$port" -lt 1 ] || [ "$port" -gt 65535 ]; then
    echo "The port must be a number from 1 to 65535."
    exit 2
fi
# A VBAN stream name is up to 16 characters; these ones need no quoting in the service file
if ! [[ "$stream_name" =~ ^[A-Za-z0-9._-]{1,16}$ ]]; then
    echo "The stream name can have up to 16 letters, digits, dots, dashes and underscores."
    exit 2
fi
# dotnet and Avalonia: no telemetry and no first-run banner
export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1 DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1 AVALONIA_TELEMETRY_OPTOUT=1

here=$(cd "$(dirname "$0")" && pwd)
install_dir="$HOME/.local/lib/starwire-mic"
bin_dir="$HOME/.local/bin"
unit_dir="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
unit="$unit_dir/starwire-mic.service"
pairings="${XDG_DATA_HOME:-$HOME/.local/share}/Starwire/mic-pairings.dat"
app="$install_dir/app/starwire-mic-app"
data_home="${XDG_DATA_HOME:-$HOME/.local/share}"
menu_entry="$data_home/applications/starwire-mic.desktop"
icon="$data_home/icons/hicolor/256x256/apps/starwire-mic.png"
autostart="${XDG_CONFIG_HOME:-$HOME/.config}/autostart/starwire-mic-app.desktop"
# Local-network and private VPN addresses (Tailscale uses 100.64.0.0/10 and fd7a::/16): the same
# ones the receiver accepts. Only paired devices can send it audio anyway; this is an extra layer.
ranges=(10.0.0.0/8 172.16.0.0/12 192.168.0.0/16 169.254.0.0/16 100.64.0.0/10 fc00::/7 fe80::/10 fec0::/10)

step() { printf '\n\033[36m== %s\033[0m\n' "$1"; }
done_() { printf '   \033[32m%s\033[0m\n' "$1"; }
note() { printf '   \033[33m%s\033[0m\n' "$1"; }
fail() { printf '\n\033[31mSetup stopped: %s\033[0m\n' "$1"; echo "Fix that and run the setup again. Nothing after this step was changed."; exit 1; }

if [ "$(id -u)" -eq 0 ]; then
    fail "run it as yourself, not with sudo. It asks for your password when it needs it (for the firewall)."
fi

echo 'Starwire microphone setup'

# The firewall: which one is on, if any
firewall_kind() {
    if command -v ufw >/dev/null && systemctl is-active --quiet ufw 2>/dev/null; then
        echo ufw
    elif command -v firewall-cmd >/dev/null && systemctl is-active --quiet firewalld 2>/dev/null; then
        echo firewalld
    else
        echo none
    fi
}

# Every rule this setup makes says "Starwire microphone", so they can be found and removed
remove_firewall_rules() {
    case "$(firewall_kind)" in
        ufw)
            # ufw numbers its rules; delete from the last so the numbers don't shift
            local numbers
            numbers=$(sudo ufw status numbered | sed -n 's/^\[ *\([0-9]*\)\].*# Starwire microphone *$/\1/p' | sort -rn)
            for n in $numbers; do
                sudo ufw --force delete "$n" >/dev/null
            done
            ;;
        firewalld)
            local rule
            while IFS= read -r rule; do
                [ -n "$rule" ] && sudo firewall-cmd --quiet --permanent --remove-rich-rule="$rule"
            done < <(sudo firewall-cmd --permanent --list-rich-rules | grep 'Starwire microphone' || true)
            sudo firewall-cmd --quiet --reload
            ;;
    esac
}

# --- Stop a receiver and an app that are running, so it's safe to run the setup again ---
if systemctl --user is-active --quiet starwire-mic 2>/dev/null; then
    systemctl --user stop starwire-mic
fi
app_was_running=0
if pgrep -f "^$app" >/dev/null 2>&1; then
    app_was_running=1
    pkill -f "^$app" || true
fi

if [ $uninstall -eq 1 ]; then
    step 'Removing the Starwire microphone receiver'
    systemctl --user disable starwire-mic >/dev/null 2>&1 || true
    rm -f "$unit"
    systemctl --user daemon-reload || true
    rm -rf "$install_dir"
    [ -L "$bin_dir/starwire-mic" ] && rm -f "$bin_dir/starwire-mic"
    rm -f "$menu_entry" "$autostart" "$icon"
    rm -f "$pairings" "$pairings.new"
    rmdir "$(dirname "$pairings")" 2>/dev/null || true
    if [ $firewall -eq 1 ] && [ "$(firewall_kind)" != none ]; then
        note 'Removing its firewall rules (this asks for your password)...'
        remove_firewall_rules
    fi
    done_ 'Removed, with the app and the list of paired devices.'
    exit 0
fi

# --- What it needs ---
step 'Checking what the receiver needs'
if ! command -v dotnet >/dev/null || ! dotnet --list-sdks 2>/dev/null | grep -q '^\(10\|1[1-9]\)\.'; then
    note "The .NET SDK (version 10 or later) builds the receiver here, and it isn't installed. Install it:"
    note '  CachyOS, Arch:  sudo pacman -S dotnet-sdk'
    note '  Fedora:         sudo dnf install dotnet-sdk-10.0'
    note '  Ubuntu, Debian: see https://learn.microsoft.com/dotnet/core/install/linux'
    fail 'the .NET SDK is missing'
fi
done_ ".NET SDK $(dotnet --list-sdks | tail -1 | cut -d' ' -f1)"
if ! command -v pw-cli >/dev/null || ! pw-cli info 0 >/dev/null 2>&1; then
    note "PipeWire isn't running for you. The receiver's microphone is a PipeWire source, so it needs PipeWire"
    note '(KDE Plasma, GNOME and most desktops use it now). It would keep trying until PipeWire starts.'
else
    done_ "PipeWire $(pw-cli info 0 2>/dev/null | sed -n 's/.*version: "\(.*\)"/\1/p' | head -1)"
fi
for f in StarwireMic.csproj StarwireMicReceiver.cs Platform.cs PipeWire.cs JitterRing.cs Control.cs ControlClient.cs PairingModel.cs ../windows/SecureMic.cs; do
    [ -f "$here/$f" ] || fail "$f must be in the Starwire folder this script came with. Get the whole folder and run it from there."
done

# --- Build and install the receiver ---
step 'Installing the Starwire microphone receiver'
work=$(mktemp -d -t starwire-mic-build.XXXXXX)
trap 'rm -rf "$work"' EXIT
if ! dotnet publish "$here/StarwireMic.csproj" -c Release -nologo -v q -o "$work/out" \
        -p:BaseOutputPath="$work/bin/" -p:BaseIntermediateOutputPath="$work/obj/" >"$work/build.txt" 2>&1; then
    grep -E 'error|warn' "$work/build.txt" | sort -u || cat "$work/build.txt"
    fail 'building the receiver failed (see above)'
fi
rm -rf "$install_dir"
had_bin_dir=0
[ -d "$bin_dir" ] && had_bin_dir=1
mkdir -p "$(dirname "$install_dir")" "$bin_dir"
cp -r "$work/out" "$install_dir"
ln -sfn "$install_dir/starwire-mic" "$bin_dir/starwire-mic"
done_ "Installed $install_dir"
# The command as the next steps say it: in full if this shell won't find it by name
cmd=starwire-mic
case ":$PATH:" in
    *":$bin_dir:"*) ;;
    *)
        cmd="$bin_dir/starwire-mic"
        if [ $had_bin_dir -eq 0 ]; then
            # Most desktops add ~/.local/bin to PATH only if it's there when a terminal opens
            note "$bin_dir is new, so terminals opened before now won't find starwire-mic by name. Open a new"
            note "terminal, or type $cmd in full."
        else
            note "$bin_dir isn't on your PATH, so type $cmd in full, or add $bin_dir to your PATH."
        fi
        ;;
esac

# --- The service ---
step 'Starting the receiver'
args="--port $port"
if [ $allow_unencrypted -eq 1 ]; then
    args="$args --allow-unencrypted --name $stream_name"
fi
mkdir -p "$unit_dir"
cat >"$unit" <<EOF
# Starwire microphone receiver: made by Starwire's linux/setup-microphone.sh. Run the setup again
# to change it, or with --uninstall to remove it.
[Unit]
Description=Starwire microphone receiver
Documentation=$(grep -o 'https://github.com/[^"]*#readme' "$here/StarwireMicReceiver.cs" | head -1)
After=pipewire.service wireplumber.service
Wants=pipewire.service

[Service]
ExecStart=$install_dir/starwire-mic $args
Restart=on-failure
RestartSec=5
# 4: the port is taken by another program, which a restart won't change
RestartPreventExitStatus=4
NoNewPrivileges=yes

[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
if [ $startup -eq 1 ]; then
    systemctl --user enable --now starwire-mic >/dev/null 2>&1
    done_ 'It starts by itself when you sign in.'
else
    systemctl --user disable starwire-mic >/dev/null 2>&1 || true
    systemctl --user start starwire-mic
    note "It's running now, but won't start by itself when you sign in (--no-startup)."
fi
sleep 2
if ! systemctl --user is-active --quiet starwire-mic; then
    journalctl --user -u starwire-mic -n 20 --no-pager -o cat || true
    fail "the receiver didn't start (see above)"
fi
done_ 'Running, with no window. See it with: systemctl --user status starwire-mic'

# --- The firewall ---
if [ $firewall -eq 1 ]; then
    kind=$(firewall_kind)
    step 'Letting the receiver through the firewall (local network and private VPNs only)'
    case "$kind" in
        ufw|firewalld)
            note "$kind is on. This asks for your password to add the rules..."
            remove_firewall_rules
            for r in "${ranges[@]}"; do
                if [ "$kind" = ufw ]; then
                    sudo ufw allow proto udp from "$r" to any port "$port" comment 'Starwire microphone' >/dev/null
                else
                    family=ipv4
                    [[ "$r" == *:* ]] && family=ipv6
                    # The log prefix is how the rules are found again; firewalld has no comments
                    sudo firewall-cmd --quiet --permanent --add-rich-rule="rule family=$family source address=$r port port=$port protocol=udp log prefix=\"Starwire microphone\" level=debug limit value=1/m accept"
                fi
            done
            [ "$kind" = firewalld ] && sudo firewall-cmd --quiet --reload
            done_ "UDP port $port, from local-network and private VPN addresses (like Tailscale)."
            ;;
        *)
            done_ "No firewall is on (ufw or firewalld), so there's nothing to change."
            ;;
    esac
fi

# --- Check the microphone ---
step 'Checking the virtual microphone'
"$install_dir/starwire-mic" check | sed 's/^/   /' || note "If it says the microphone isn't there, see: journalctl --user -u starwire-mic"

# --- The app ---
app_ok=0
if [ $with_app -eq 1 ]; then
    step 'Installing the Starwire microphone app'
    if ! dotnet publish "$here/app/StarwireMicApp.csproj" -c Release -nologo -v q -o "$work/app" -p:RestoreLockedMode=true \
            -p:BaseOutputPath="$work/app-bin/" -p:BaseIntermediateOutputPath="$work/app-obj/" >"$work/app-build.txt" 2>&1; then
        grep -E 'error|warn' "$work/app-build.txt" | sort -u | head -20 || cat "$work/app-build.txt"
        note "Building the app failed (see above), so it isn't installed. The receiver works without it, and the"
        note "starwire-mic command does what the app does. Building it needs the internet once, for Avalonia."
        # An app from an earlier setup went with the receiver's folder: no menu entry to nothing
        rm -f "$menu_entry" "$autostart"
    else
        rm -rf "$install_dir/app"
        cp -r "$work/app" "$install_dir/app"
        mkdir -p "$(dirname "$menu_entry")" "$(dirname "$icon")" "$(dirname "$autostart")"
        "$app" --write-icon "$icon"
        desktop_entry() {
            printf '%s\n' '[Desktop Entry]' 'Type=Application' 'Name=Starwire microphone' 'GenericName=Phone microphone' \
                "Comment=Use your Android device's microphone on this PC, sent from Starwire" "Exec=$app$1" 'Icon=starwire-mic' \
                'Terminal=false' 'Categories=AudioVideo;Audio;Utility;' 'Keywords=microphone;mic;Starwire;Android;phone;voice;Discord;'
        }
        desktop_entry "" >"$menu_entry"
        { desktop_entry " --tray"; echo 'X-GNOME-Autostart-enabled=true'; } >"$autostart"
        # The app menu picks the new entry up by itself; this only makes it quicker where it can
        if command -v update-desktop-database >/dev/null; then
            update-desktop-database -q "$(dirname "$menu_entry")" 2>/dev/null || true
        fi
        done_ "Installed $install_dir/app. It's in the app menu as Starwire microphone, and its tray icon starts when you sign in."
        app_ok=1
        # Started now too, and left running when the setup ends: the window, or only the tray icon
        # if it was running before
        if [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]; then
            if [ $app_was_running -eq 1 ]; then
                setsid -f "$app" --tray >/dev/null 2>&1 < /dev/null || true
            else
                setsid -f "$app" >/dev/null 2>&1 < /dev/null || true
            fi
        fi
    fi
fi

# --- What to do next ---
step 'Next'
echo '   1. In Discord or the game, choose "Starwire microphone" as the microphone.'
if [ $app_ok -eq 1 ]; then
    echo '   2. Pair Starwire with this PC, once: open the Starwire microphone app, choose Pair a device, and'
    echo '      type the code it shows into Starwire: Settings > Sound & vibration > Send my microphone >'
    echo "      Pair with the PC. (Or run  $cmd pair  in a terminal.)"
else
    echo "   2. Pair Starwire with this PC, once: run  $cmd pair  and type the code it shows into"
    echo '      Starwire: Settings > Sound & vibration > Send my microphone > Pair with the PC.'
fi
[ "$port" -ne 6980 ] && echo "   3. Set Starwire's microphone port to $port, like this PC's."
if [ $allow_unencrypted -eq 1 ]; then
    note "--allow-unencrypted is on: the receiver also plays plain VBAN named \"$stream_name\", which anyone on this network can send or listen to. Run the setup again without it to turn that off."
fi
addresses=$(ip -4 -o addr show scope global 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | paste -sd, - | sed 's/,/, /g')
[ -n "$addresses" ] && echo "   This PC's addresses: $addresses"
exit 0
