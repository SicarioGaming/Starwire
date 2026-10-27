package com.limelight.usbip;

/** JNI owns the duplicated FDs. One exporter (a handle) serves any number of
 * devices, each bound by its busid. All calls are serialized by their owner
 * (UsbIpBackend, or Starwire's UsbExporter, which uses one exporter per shared
 * device), and a handle is never reused after stop(). Failures throw
 * java.io.IOException, which the native methods don't declare. */
public final class NativeUsbIp {
    private static boolean loaded;
    public static synchronized void load() {
        if (!loaded) {
            System.loadLibrary("moonlight_usbip");
            loaded = true;
        }
    }
    public static native long start();
    public static native int localPort(long handle);
    public static native void authorizeLocalConnection(long handle, int sourcePort);
    public static native void revokeLocalConnection(long handle, int sourcePort);
    public static native String bind(long handle, int fd);
    public static native void unbind(long handle, String busId);
    public static native void stop(long handle);
    // Starwire: what its device rules read before a device is exported
    /** The device's speed, as libusb's LIBUSB_SPEED_* (1 low, 2 full, 3 high, 4 and up USB 3). */
    public static native int speed(int fd);
    /** One HID interface's report descriptor, at most length bytes. */
    public static native byte[] reportDescriptor(int fd, int interfaceNumber, int length);
    private NativeUsbIp() {}
}
