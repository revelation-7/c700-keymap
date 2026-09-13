package com.c700.keymapd;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Persistent line client for the root injector daemon (loopback TCP).
 * Protocol: send "<keycode> <hold_ms>\n", expect "OK\n" per line ("HI\n" on connect).
 * A reader thread tracks ACKs; if a line is not ACKed within ACK_TIMEOUT_MS the
 * connection is torn down and the line requeued once, which also heals half-open
 * sockets left behind by daemon restarts.
 */
public class KeyInjector {

    private static final String TAG = "c700-keymapd";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 57521;
    private static final long ACK_TIMEOUT_MS = 700;

    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(64);
    private final String[] unacked = new String[1];   // line awaiting ACK
    private volatile boolean sockAlive;
    private volatile Socket sockRef;
    private Thread worker;
    private volatile long lastAckAt;

    public synchronized void start() {
        if (worker != null) return;
        worker = new Thread(this::loop, "c700-inject");
        worker.setDaemon(true);
        worker.start();
    }

    public boolean connected() {
        return sockAlive;
    }

    // hz>0 enables turbo rapid-fire at hz clicks/s; hz==0 = normal press
    public void press(int keyCode, int hz) {
        if (hz > 0) queue.offer("D " + keyCode + " ! " + hz + "\n");
        else queue.offer("D " + keyCode + "\n");
    }

    public void release(int keyCode) {
        queue.offer("U " + keyCode + "\n");
    }

    private void loop() {
        String sockNullLine = null; // line we last wrote, awaiting ACK
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (!sockAlive) {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(HOST, PORT), 800);
                    s.setTcpNoDelay(true);
                    s.setKeepAlive(true);
                    sockRef = s;
                    sockAlive = true;
                    lastAckAt = System.currentTimeMillis();
                    startReader(s);
                    Log.i(TAG, "inject daemon connected");
                }
                String line = queue.poll(2, TimeUnit.SECONDS);
                if (line == null) {
                    // liveness: no traffic, no pending ACK -> healthy
                    continue;
                }
                OutputStream os = sockRef != null ? sockRef.getOutputStream() : null;
                if (os == null) throw new java.io.IOException("no socket");
                unacked[0] = line;
                os.write(line.getBytes(StandardCharsets.US_ASCII));
                os.flush();
                // wait for ACK asynchronously via reader thread
                long deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS;
                while (unacked[0] != null && System.currentTimeMillis() < deadline) {
                    Thread.sleep(5);
                }
                if (unacked[0] != null) {
                    // no ACK -> dead link: heal, requeue once
                    String lost = unacked[0];
                    unacked[0] = null;
                    dropSocket();
                    if (!lost.equals(sockNullLine)) {
                        sockNullLine = lost;
                        queue.offer(lost);
                    } else {
                        sockNullLine = null; // avoid endless ping-pong
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "inject loop err: " + e);
                dropSocketQuiet();
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void startReader(Socket s) {
        Thread r = new Thread(() -> {
            try {
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("OK")) {
                        unacked[0] = null;
                        lastAckAt = System.currentTimeMillis();
                    }
                }
            } catch (Exception ignored) {
            }
            Log.w(TAG, "inject reader eof");
            dropSocket();
        }, "c700-inject-rx");
        r.setDaemon(true);
        r.start();
    }

    private void dropSocket() {
        sockAlive = false;
        Socket s = sockRef;
        sockRef = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void dropSocketQuiet() {
        dropSocket();
    }
}
