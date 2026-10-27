package com.limelight.binding.usb.tunnel;

import java.io.Closeable;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;

/**
 * Which TLS the tunnel's tests run on, set for the whole JVM while a test runs and put back after:
 * the JDK's own (SunJSSE), or Conscrypt, the BoringSSL-based TLS Android uses, in its desktop
 * build (Robolectric brings it). Robolectric's tests can leave Conscrypt installed first, so
 * without this the TLS under test would depend on which tests ran before.
 */
final class TlsProvider implements Closeable {
    static final String JDK = "SunJSSE";
    static final String CONSCRYPT = "Conscrypt";

    private final Provider[] before = Security.getProviders();

    private TlsProvider(String which) throws Exception {
        removeConscrypt();
        if (CONSCRYPT.equals(which)) {
            Provider conscrypt = (Provider) Class.forName("org.conscrypt.Conscrypt").getMethod("newProvider").invoke(null);
            Security.insertProviderAt(conscrypt, 1);
        }
        else if (!JDK.equals(which)) {
            throw new IllegalArgumentException(which);
        }
    }

    static TlsProvider use(String which) throws Exception {
        return new TlsProvider(which);
    }

    /** A SecureRandom that gives the same bytes for the same seed, whichever provider is first. */
    static SecureRandom seeded(long seed) throws Exception {
        SecureRandom r = SecureRandom.getInstance("SHA1PRNG", "SUN");
        r.setSeed(seed);
        return r;
    }

    private static boolean isConscrypt(Provider p) {
        return p.getClass().getName().startsWith("org.conscrypt.");
    }

    private static void removeConscrypt() {
        for (Provider p : Security.getProviders()) {
            if (isConscrypt(p)) {
                Security.removeProvider(p.getName());
            }
        }
    }

    @Override
    public void close() {
        removeConscrypt();
        for (int i = 0; i < before.length; i++) {
            if (isConscrypt(before[i])) {
                Security.insertProviderAt(before[i], i + 1);
            }
        }
    }
}
