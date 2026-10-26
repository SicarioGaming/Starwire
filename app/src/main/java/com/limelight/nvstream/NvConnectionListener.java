package com.limelight.nvstream;

public interface NvConnectionListener {
    void stageStarting(String stage);
    void stageComplete(String stage);
    void stageFailed(String stage, int portFlags, int errorCode);
    
    void connectionStarted();
    void connectionTerminated(int errorCode);
    void connectionStatusUpdate(int connectionStatus);
    
    void displayMessage(String message);
    void displayTransientMessage(String message);

    // The launch can't go ahead, for the reason in message. Shown in place of the
    // generic failure for the stageFailed() that follows.
    void launchFailed(String message);

    void rumble(short controllerNumber, short lowFreqMotor, short highFreqMotor);
    void rumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger);

    void setHdrMode(boolean enabled, byte[] hdrMetadata);

    void setMotionEventState(short controllerNumber, byte motionType, short reportRateHz);

    void setControllerLED(short controllerNumber, byte r, byte g, byte b);

    // The host's DualSense trigger effects (control message 0x5503): which triggers (0x04 right,
    // 0x08 left), each one's mode, and its 10 parameter bytes. A default, so listeners that
    // don't use it needn't say so.
    default void setAdaptiveTriggers(short controllerNumber, byte eventFlags, byte typeLeft,
                                     byte typeRight, byte[] left, byte[] right) {
    }
}
