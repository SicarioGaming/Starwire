package com.limelight.binding.usb.tunnel;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for the app's USB/IP exporter: it listens on 127.0.0.1 like the real one, hands the
 * tunnel a SHARE frame, and remembers which source port it was told to let in, so a test can check
 * the connection really comes from there.
 */
final class FakeExporter implements UsbTunnel.Exporter, Closeable {
    final ServerSocket server;
    volatile byte[] share;
    volatile IOException failStart;
    volatile int authorized = -1;
    final AtomicInteger starts = new AtomicInteger();
    final AtomicInteger stops = new AtomicInteger();

    FakeExporter(byte[] share) throws IOException {
        this.share = share;
        server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(FakeHelper.SAFETY_MS);
    }

    int port() {
        return server.getLocalPort();
    }

    @Override
    public byte[] start() throws IOException {
        starts.incrementAndGet();
        if (failStart != null) {
            throw failStart;
        }
        return share;
    }

    @Override
    public void authorize(int sourcePort) {
        authorized = sourcePort;
    }

    @Override
    public void stop() {
        stops.incrementAndGet();
    }

    /** The tunnel's connection, which must come from the port it authorised. */
    Socket accept() throws IOException {
        Socket s = server.accept();
        s.setSoTimeout(FakeHelper.SAFETY_MS);
        if (s.getPort() != authorized) {
            s.close();
            throw new IOException("a connection from port " + s.getPort() + ", but " + authorized + " was authorised");
        }
        return s;
    }

    /** Whether anything connected, once the tunnel has ended (so it would already be waiting). */
    boolean anyoneConnected() throws IOException {
        server.setSoTimeout(1);
        try (Socket s = server.accept()) {
            return s != null;
        }
        catch (java.net.SocketTimeoutException e) {
            return false;
        }
        finally {
            server.setSoTimeout(FakeHelper.SAFETY_MS);
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
