package com.limelight.binding.audio.securemic;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A pairing put in MicPairingStore's file for JVM tests outside this package. Robolectric has no
 * Android Keystore, so MicPairingStore.save() can't wrap the key; this keeps it as the store does
 * on Android 5, which MicPairingStore.forComputer() reads on any version. The device tests
 * (MicPairingStoreTest) cover save() itself.
 */
public final class StoredPairings {
    private StoredPairings() {
    }

    public static void put(Context context, MicPairing pairing) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("v", 1);
        o.put("deviceId", MicPairingStore.hex(pairing.deviceId));
        o.put("key", "raw1:" + MicPairingStore.hex(pairing.key));
        o.put("receiverId", MicPairingStore.hex(pairing.receiverId));
        o.put("receiverName", pairing.receiverName);
        o.put("computerName", pairing.computerName);
        o.put("pairedAt", pairing.pairedAtMs);
        if (!context.getApplicationContext().getSharedPreferences(MicPairingStore.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(pairing.computerUuid, o.toString()).commit()) {
            throw new IllegalStateException("couldn't write the pairing");
        }
    }
}
