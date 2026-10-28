package com.limelight.binding.usb.tunnel;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for the app's USB/IP exporter: it listens on 127.0.0.1 like the real one, hands the
 * tunnel a SHARE frame, and makes the tunnel's one connection as UsbExporter.connect does (a
 * socket bound first, its source port authorised, then connected), so a test can check the
 * connection it accepts really is that one.
 */
final class FakeExporter implements UsbTunnel.Exporter, Closeable {
    final ServerSocket server;
    volatile byte[] share;
    volatile IOException failStart;
    volatile IOException failConnect;
    // Hands back a socket that isn't connected, as a broken exporter might
    volatile boolean unconnected;
    // Runs in connect(), before the connection is made
    volatile Runnable beforeConnect;
    volatile int authorized = -1;
    // start() says it's begun, then waits for this, if set
    volatile CountDownLatch startBegun;
    volatile CountDownLatch startWaitsFor;
    // How many devices it holds now, and at most: start() takes one, stop() gives it back
    final AtomicInteger held = new AtomicInteger();
    volatile int mostHeld;
    final AtomicInteger starts = new AtomicInteger();
    final AtomicInteger connects = new AtomicInteger();
    final AtomicInteger stops = new AtomicInteger();

    FakeExporter(byte[] share) throws IOException {
        this.share = share;
        server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(FakeHelper.SAFETY_MS);
    }

    @Override
    public byte[] start() throws IOException {
        starts.incrementAndGet();
        mostHeld = Math.max(mostHeld, held.incrementAndGet());
        CountDownLatch begun = startBegun;
        CountDownLatch until = startWaitsFor;
        if (begun != null) {
            begun.countDown();
        }
        if (until != null) {
            try {
                if (!until.await(FakeHelper.SAFETY_MS, TimeUnit.MILLISECONDS)) {
                    throw new IOException("the test never let start() return");
                }
            }
            catch (InterruptedException e) {
                throw new IOException(e);
            }
        }
        if (failStart != null) {
            throw failStart;
        }
        return share;
    }

    @Override
    public Socket connect(int timeoutMs) throws IOException {
        connects.incrementAndGet();
        Runnable before = beforeConnect;
        if (before != null) {
            before.run();
        }
        if (failConnect != null) {
            throw failConnect;
        }
        if (unconnected) {
            return new Socket();
        }
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        Socket s = new Socket();
        s.setReuseAddress(false);
        s.bind(new InetSocketAddress(loopback, 0));
        authorized = s.getLocalPort();
        s.connect(new InetSocketAddress(loopback, server.getLocalPort()), timeoutMs);
        return s;
    }

    @Override
    public void stop() {
        stops.incrementAndGet();
        held.decrementAndGet();
    }

    /** The tunnel's connection, which must come from the port it authorised. */
    Socket accept() throws IOException {
        Socket s;
        try {
            s = server.accept();
        }
        catch (SocketTimeoutException e) {
            throw StuckThreads.timedOut("the tunnel never connected to the exporter in " + server.getSoTimeout() + " ms", e);
        }
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
        catch (SocketTimeoutException e) {
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
