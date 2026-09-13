package com.c700.keymapd;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Resident root injector. Protocol lines from the app:
 *   "D <keycode> [P]"  mirror physical key DOWN  (P = spoof vendor pad device)
 *   "U <keycode>"      mirror physical key UP (real downTime preserved)
 *   "<keycode> [ms]"   legacy one-shot tap (auto-release after ms)
 * Answers "OK\n" per processed line, "HI\n" on connect. A watchdog releases
 * any key held longer than HOLD_MAX_MS (e.g. after a dropped connection).
 */
public class InjectDaemon {

    private static final long HOLD_MAX_MS = 5000;

    private static Object sInputManager;
    private static Method sInject;
    private static Handler sHandler;
    private static int sPadId = KeyCharacterMap.VIRTUAL_KEYBOARD;
    private static int sPadSource = InputDevice.SOURCE_KEYBOARD;
    private static long sPadScanAt;
    private static final Object sLock = new Object();
    private static final Map<Integer, Pending> sHeld = new HashMap<>();

    private static final class Pending {
        long downTime;
        int devId, scan, source;
        Runnable watchdog;
        Runnable turbo;
        boolean turboOn;
        int halfMs = 45; // per-stroke half-period for rapid-fire
    }

    private static boolean isGamepadKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BUTTON_X: case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_L1: case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_L2: case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_BUTTON_THUMBL: case KeyEvent.KEYCODE_BUTTON_THUMBR:
            case KeyEvent.KEYCODE_BUTTON_SELECT: case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_BUTTON_MODE: case KeyEvent.KEYCODE_BUTTON_C:
            case KeyEvent.KEYCODE_BUTTON_Z:
                return true;
            default:
                return false;
        }
    }

    private static int scanCodeOf(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A:  return 304;
            case KeyEvent.KEYCODE_BUTTON_B:  return 305;
            case KeyEvent.KEYCODE_BUTTON_X:  return 307;
            case KeyEvent.KEYCODE_BUTTON_Y:  return 306;
            case KeyEvent.KEYCODE_BUTTON_L1: return 310;
            case KeyEvent.KEYCODE_BUTTON_R1: return 311;
            case KeyEvent.KEYCODE_BUTTON_L2: return 312;
            case KeyEvent.KEYCODE_BUTTON_R2: return 313;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return 314;
            case KeyEvent.KEYCODE_BUTTON_START:  return 315;
            case KeyEvent.KEYCODE_BUTTON_MODE:   return 316;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return 317;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return 318;
            default: return 0;
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 57521;
        HandlerThread ht = new HandlerThread("inject-timer");
        ht.start();
        sHandler = new Handler(ht.getLooper());
        if (!initInputManager()) {
            System.err.println("injectd: no InputManagerGlobal");
            System.exit(1);
        }
        try (ServerSocket ss = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))) {
            System.out.println("injectd listening on " + port);
            while (true) {
                final Socket s = ss.accept();
                new Thread(() -> {
                    try (Socket sk = s) {
                        serve(sk);
                    } catch (Exception ignored) {
                    }
                }, "injectd-conn").start();
            }
        }
    }

    private static boolean initInputManager() {
        try {
            android.os.Looper.prepareMainLooper(); // binder callback infra
            Class<?> clz = Class.forName("android.hardware.input.InputManagerGlobal");
            try {
                Method gi = clz.getMethod("getInstance", android.content.Context.class);
                sInputManager = gi.invoke(null, new Object[]{null});
            } catch (NoSuchMethodException e) {
                Method gi = clz.getMethod("getInstance");
                sInputManager = gi.invoke(null);
            }
            sInject = sInputManager.getClass()
                    .getMethod("injectInputEvent", InputEvent.class, int.class);
            return sInputManager != null && sInject != null;
        } catch (Throwable t) {
            System.err.println("injectd init failed: " + t);
            return false;
        }
    }

    private static void serve(Socket s) {
        try {
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            OutputStream out = s.getOutputStream();
            out.write("HI\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    handle(line, out);
                } catch (Exception e) {
                    System.err.println("injectd line '" + line + "': " + e
                            + (e.getCause() != null ? " cause=" + e.getCause() : ""));
                }
            }
        } catch (Exception ignored) {
        } finally {
            releaseAll(); // client gone: never leave keys stuck
        }
    }

    private static void handle(String line, OutputStream out) throws Exception {
        String[] p = line.trim().split("\\s+");
        if (p.length < 2) return;
        char cmd = p[0].charAt(0);
        int code;
        try {
            code = Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
            return;
        }
        if (code < 1 || code > 1000) return;
        if (cmd == 'D' || cmd == 'd') {
            // gamepad keys mirror onto the vendor pad device by default;
            // flags: "P" force spoof, "V" force virtual, "!" turbo (rapid fire)
            boolean optP = false, optV = false, turbo = false;
            int hz = 0;
            for (int i = 2; i < p.length; i++) {
                if (p[i].equals("P")) optP = true;
                else if (p[i].equals("V")) optV = true;
                else if (p[i].equals("!")) turbo = true;
                else { try { hz = Integer.parseInt(p[i]); } catch (NumberFormatException ignored) {} }
            }
            boolean spoof = optP || (!optV && isGamepadKey(code));
            System.out.println("D " + code + " spoof=" + spoof + " turbo=" + turbo + " hz=" + hz);
            pressDown(code, spoof, turbo, hz);
        } else if (cmd == 'U' || cmd == 'u') {
            System.out.println("U " + code);
            pressUp(code);
        } else {
            return; // D/U protocol only
        }
        out.write("OK\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static void pressDown(int code, boolean spoof, boolean turbo, int hz) {
        synchronized (sLock) {
            if (sHeld.containsKey(code)) return; // duplicate DOWN guard
            int devId, source, scan = 0;
            if (spoof && isGamepadKey(code)) {
                refreshPad();
                devId = sPadId;
                source = sPadSource;
                scan = scanCodeOf(code);
            } else {
                devId = KeyCharacterMap.VIRTUAL_KEYBOARD;
                source = InputDevice.SOURCE_KEYBOARD;
            }
            long now = SystemClock.uptimeMillis();
            Pending pd = new Pending();
            pd.downTime = now;
            pd.devId = devId;
            pd.scan = scan;
            pd.source = source;
            final int fCode = code, fDev = devId, fScan = scan, fSrc = source;
            pd.watchdog = () -> {
                synchronized (sLock) {
                    if (sHeld.get(fCode) == pd) {
                        sHeld.remove(fCode);
                        injectKey(pd.downTime, SystemClock.uptimeMillis(),
                                KeyEvent.ACTION_UP, fCode, fDev, fScan, fSrc);
                    }
                }
            };
            sHandler.postDelayed(pd.watchdog, HOLD_MAX_MS);
            sHeld.put(code, pd);
            injectKey(now, now, KeyEvent.ACTION_DOWN, code, devId, scan, source);
            if (turbo) {
                pd.turboOn = true;
                int h = hz <= 0 ? 10 : hz;
                pd.halfMs = Math.max(15, Math.min(250, 500 / h)); // each stroke = half cycle
                scheduleTurbo(pd, fCode, true);
            }
        }
    }

    /** rapid-fire: alternate UP/DOWN every 45ms (~11 shots/s) while held */
    private static void scheduleTurbo(Pending pd, final int code, final boolean upNext) {
        pd.turbo = () -> {
            synchronized (sLock) {
                if (sHeld.get(code) != pd || !pd.turboOn) return;
                long now = SystemClock.uptimeMillis();
                if (!upNext) pd.downTime = now; // new stroke starts
                injectKey(pd.downTime, now,
                        upNext ? KeyEvent.ACTION_UP : KeyEvent.ACTION_DOWN,
                        code, pd.devId, pd.scan, pd.source);
                scheduleTurbo(pd, code, !upNext);
            }
        };
        sHandler.postDelayed(pd.turbo, pd.halfMs);
    }

    private static void pressUp(int code) {
        synchronized (sLock) {
            Pending pd = sHeld.remove(code);
            if (pd == null) return;
            sHandler.removeCallbacks(pd.watchdog);
            if (pd.turbo != null) sHandler.removeCallbacks(pd.turbo);
            injectKey(pd.downTime, SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_UP, code, pd.devId, pd.scan, pd.source);
        }
    }

    private static void releaseAll() {
        synchronized (sLock) {
            for (Map.Entry<Integer, Pending> e : sHeld.entrySet()) {
                Pending pd = e.getValue();
                sHandler.removeCallbacks(pd.watchdog);
                if (pd.turbo != null) sHandler.removeCallbacks(pd.turbo);
                injectKey(pd.downTime, SystemClock.uptimeMillis(),
                        KeyEvent.ACTION_UP, e.getKey(), pd.devId, pd.scan, pd.source);
            }
            sHeld.clear();
        }
    }

    private static void injectKey(long downTime, long eventTime, int action,
                                  int code, int devId, int scan, int source) {
        try {
            KeyEvent k = new KeyEvent(downTime, eventTime, action, code, 0, 0,
                    devId, scan, KeyEvent.FLAG_FROM_SYSTEM, source);
            sInject.invoke(sInputManager, k, 0); // MODE_ASYNC
        } catch (Exception e) {
            System.err.println("inject failed: " + e);
        }
    }

    private static void refreshPad() {
        long now = SystemClock.uptimeMillis();
        if (now - sPadScanAt < 5000) return;
        sPadScanAt = now;
        android.view.InputDevice[] devs = null;
        try {
            int[] ids = android.view.InputDevice.getDeviceIds();
            if (ids != null && ids.length > 0) {
                devs = new android.view.InputDevice[ids.length];
                for (int i = 0; i < ids.length; i++)
                    devs[i] = android.view.InputDevice.getDevice(ids[i]);
            }
        } catch (Throwable t) {
            System.err.println("InputDevice enum failed: " + t);
        }
        if (devs != null) {
            for (android.view.InputDevice d : devs) {
                if (d != null && d.getName() != null && d.getName().contains("X-box 360")) {
                    if (sPadId != d.getId()) {
                        System.out.println("pad devId=" + d.getId() + " src=0x"
                                + Integer.toHexString(d.getSources()));
                    }
                    sPadId = d.getId();
                    sPadSource = d.getSources();
                    return;
                }
            }
        }
        sPadId = KeyCharacterMap.VIRTUAL_KEYBOARD;
        sPadSource = InputDevice.SOURCE_KEYBOARD;
    }
}
