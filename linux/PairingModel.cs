// Starwire microphone receiver for Linux: what a pairing says (GPL-3.0, part of Starwire).
//
// The receiver's answer to "pair" is a stream of lines (Control.cs lists them). This turns them into
// where the pairing stands and the words to show, so `starwire-mic pair` and the app's pairing
// window say the same things. The words follow the Windows receiver's pairing window.

using System;

enum PairingStep { Waiting, Open, Paired, Expired, TooManyTries, UsedTwice, Replaced, Cancelled, ReceiverStopped }

sealed class PairingModel
{
    public const string Instructions = "In Starwire on your Android device: Settings > Sound & vibration > Send my microphone > "
        + "Pair with the PC, then type the code this shows.";
    public const string Caution = "Anyone who sees the code before you've typed it could pair instead of you, so don't show it "
        + "while your screen is shared.";

    public PairingStep Step = PairingStep.Waiting;
    public string Code;
    public int SecondsLeft;
    public string DeviceName, Address, DeviceId;

    // Takes one line from the receiver; true if anything changed
    public bool Feed(string line)
    {
        PairingStep before = Step;
        int left = SecondsLeft;
        if (line.StartsWith("code ")) {
            Code = line.Substring(5);
            Step = PairingStep.Open;
            return true;
        }
        if (line.StartsWith("open ")) {
            int s;
            if (int.TryParse(line.Substring(5), out s)) {
                SecondsLeft = s;
                Step = PairingStep.Open;
            }
        }
        else if (line.StartsWith("paired\t")) {
            string[] f = line.Split('\t');
            if (f.Length == 4) {
                DeviceName = f[1];
                Address = f[2];
                DeviceId = f[3];
                Step = PairingStep.Paired;
            }
        }
        else {
            switch (line) {
                case "expired": Step = PairingStep.Expired; break;
                case "toomany": Step = PairingStep.TooManyTries; break;
                case "usedtwice": Step = PairingStep.UsedTwice; break;
                case "replaced": Step = PairingStep.Replaced; break;
                default: Step = PairingStep.Cancelled; break;
            }
        }
        return Step != before || SecondsLeft != left;
    }

    // The receiver closed the connection without saying how it ended
    public void Closed()
    {
        if (Step == PairingStep.Waiting || Step == PairingStep.Open) {
            Step = PairingStep.ReceiverStopped;
        }
    }

    public bool Finished { get { return Step != PairingStep.Waiting && Step != PairingStep.Open; } }

    // "It works once, for the next 4:59."
    public string TimeLeft
    {
        get { return "It works once, for the next " + SecondsLeft / 60 + ":" + (SecondsLeft % 60).ToString("00") + "."; }
    }

    // How it ended, in big letters, then what it means
    public string Title
    {
        get {
            switch (Step) {
                case PairingStep.Paired: return "Paired with " + DeviceName + ".";
                case PairingStep.Expired: return "That code has expired.";
                case PairingStep.TooManyTries: return "Too many wrong codes.";
                case PairingStep.UsedTwice: return "The code was used twice.";
                case PairingStep.Replaced: return "A new code was made.";
                case PairingStep.ReceiverStopped: return "The receiver stopped.";
                default: return "Pairing stopped.";
            }
        }
    }

    // again: how to get a new code, in this program's words ("Run starwire-mic pair again", say)
    public string Detail(string again)
    {
        switch (Step) {
            case PairingStep.Paired:
                return "From " + Address + ". Its microphone comes to this PC encrypted from now on.";
            case PairingStep.Expired:
                return again + " for a new code.";
            case PairingStep.TooManyTries:
                return "Pairing stopped after too many wrong codes. " + again + " for a new code.";
            case PairingStep.UsedTwice:
                return "So someone else may have seen it. To be safe, no device is paired with it. " + again + " for a new code.";
            case PairingStep.Replaced:
                return "Another pairing made a new code, so this one stopped working.";
            case PairingStep.ReceiverStopped:
                return "Start it again, then pair again.";
            default:
                return again + " for a new code.";
        }
    }

    public const string NotMine = "If you haven't typed the code yet, or that isn't your device, someone else saw the code:";
}
