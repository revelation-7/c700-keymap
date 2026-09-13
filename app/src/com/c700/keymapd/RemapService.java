package com.c700.keymapd;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * c700-keymap v2: intercept vendor gamepad back/custom keys (F10/F11/F12)
 * before apps see them, inject mapped keycode instead, swallow the original.
 *
 * Config text (SharedPreferences "keymap"/"raw"), v1-compatible format:
 *   F10=SYSRQ          global mapping
 *   F11=A
 *   [com.foo.game]     per-app section: overrides global for that pkg
 *   F11=B
 *   F12=-              "-" = explicitly pass-through (hand key back to app/vendor)
 */
public class RemapService extends AccessibilityService {

    static final String TAG = "c700-keymapd";
    static final String PREFS = "keymap";
    static final String PREF_RAW = "raw";

    private final Map<Integer, Integer> globalMap = new HashMap<>();
    private final Map<String, Map<Integer, Integer>> appSections = new HashMap<>();
    private final Map<Integer, Integer> globalRate = new HashMap<>();
    private final Map<String, Map<Integer, Integer>> appRateSections = new HashMap<>();
    private final Map<Integer, Integer> effective = new HashMap<>();
    private final Map<Integer, Integer> effectiveRate = new HashMap<>();
    // source keycode -> injected target keycode, for releasing on UP
    private final Map<Integer, Integer> held = new HashMap<>();

    private String fgPkg = "";
    public static volatile boolean sConnected = false;

    private java.lang.reflect.Method dispatchMethod;
    public static volatile KeyInjector injector;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (p, key) -> {
                if (PREF_RAW.equals(key)) {
                    reload(p.getString(PREF_RAW, ""));
                }
            };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 50;
        setServiceInfo(info);
        try {
            dispatchMethod = AccessibilityService.class.getMethod(
                    "dispatchInputEvent", int.class, android.view.InputEvent.class);
            Log.i(TAG, "dispatchInputEvent available");
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "dispatchInputEvent unavailable (needs INJECT_EVENTS via priv-app); "
                    + "screenshot mapping still works via performGlobalAction");
        }
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        sp.registerOnSharedPreferenceChangeListener(prefsListener);
        reload(sp.getString(PREF_RAW, ""));
        if (injector == null) injector = new KeyInjector();
        injector.start();
        sConnected = true;
        Log.i(TAG, "service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg = event.getPackageName();
            String p = pkg == null ? "" : pkg.toString();
            if (!p.isEmpty() && !p.equals(fgPkg) && !"[unknown]".equals(p)) {
                fgPkg = p;
                rebuildEffective();
            }
        }
    }

    @Override
    public boolean onKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        int action = ev.getAction();
        Log.d(TAG, "key " + KeyEvent.keyCodeToString(code) + " " + action + " src=" + ev.getSource());
        Integer target;
        synchronized (effective) {
            target = effective.get(code);
        }
        if (target == null) return false; // not ours: pass to vendor service / app
        if (action == KeyEvent.ACTION_DOWN) {
            if (ev.getRepeatCount() > 0) return true; // swallow autorepeat
            synchronized (held) { held.put(code, target); }
            int hz;
            synchronized (effective) {
                Integer r = effectiveRate.get(code);
                hz = r == null ? 0 : r;
            }
            trigger(ev, target, hz);
            return true;
        }
        if (action == KeyEvent.ACTION_UP) {
            Integer t;
            synchronized (held) { t = held.remove(code); }
            if (t != null) {
                if (t == 299 || t == KeyEvent.KEYCODE_SYSRQ) {
                    // close the SYSRQ fallback stroke if it was used
                    if (injector != null && injector.connected()) injector.release(KeyEvent.KEYCODE_SYSRQ);
                } else if (dispatchMethod != null) {
                    dispatchKey(ev, t, KeyEvent.ACTION_UP);
                } else if (injector != null && injector.connected()) {
                    injector.release(t);
                }
            }
            return true;
        }
        return true; // ACTION_MULTIPLE: swallow, nothing sensible to forward
    }

    /** one press = one action; fires on DOWN only */
    private void trigger(KeyEvent ev, int targetCode, int hz) {
        if (targetCode == 299 /* KeyEvent.KEYCODE_SNAPSHOT (hidden) */
                || targetCode == KeyEvent.KEYCODE_SYSRQ) {
            // native screenshot: public API first (needs canTakeScreenshot),
            // then fall back to injecting legacy SYSRQ this ROM honors
            boolean ok = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
            Log.i(TAG, "screenshot via performGlobalAction -> " + ok);
            if (!ok && injector != null && injector.connected()) {
                injector.press(KeyEvent.KEYCODE_SYSRQ, 0);
                Log.i(TAG, "screenshot fallback: injected SYSRQ");
            }
            return;
        }
        if (dispatchMethod != null) {
            dispatchKey(ev, targetCode, KeyEvent.ACTION_DOWN);
            return;
        }
        if (injector.connected()) {
            injector.press(targetCode, hz);
            return;
        }
        Log.w(TAG, "no injection channel for keycode " + targetCode);
    }

    private void dispatchKey(KeyEvent ev, int targetCode, int action) {
        long now = SystemClock.uptimeMillis();
        long downTime = action == KeyEvent.ACTION_DOWN ? now : Math.max(ev.getDownTime(), 1);
        int source = ev.getSource() != 0 ? ev.getSource() : InputDevice.SOURCE_KEYBOARD;
        KeyEvent k = new KeyEvent(downTime, now, action, targetCode, 0,
                ev.getMetaState(), KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM, source);
        try {
            Object ok = dispatchMethod.invoke(this, 0, k);
            if (!Boolean.TRUE.equals(ok)) Log.w(TAG, "dispatchInputEvent refused: " + targetCode);
        } catch (Exception e) {
            Log.e(TAG, "inject failed", e);
        }
    }

    // ---------- config ----------

    void reload(String raw) {
        Map<Integer, Integer> g = new HashMap<>();
        Map<String, Map<Integer, Integer>> secs = new HashMap<>();
        Map<Integer, Integer> gRate = new HashMap<>();
        Map<String, Map<Integer, Integer>> sRate = new HashMap<>();
        String section = "";
        for (String line : (raw == null ? "" : raw).split("[\\r\\n;]+")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim();
                secs.put(section, new HashMap<>());
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            int src = keyCodeFromName(line.substring(0, eq).trim().toUpperCase());
            if (src == 0) continue;
            String rawv = line.substring(eq + 1);
            int hpos = rawv.indexOf('#');
            if (hpos >= 0) rawv = rawv.substring(0, hpos);
            String tv = rawv.trim().toUpperCase();
            Map<Integer, Integer> m = section.isEmpty() ? g : secs.get(section);
            if (m == null) continue;
            if (tv.equals("-")) {
                m.put(src, null); // explicit pass-through marker
            } else {
                int hz = 0;
                int bang = tv.indexOf('!');
                if (bang >= 0) { // turbo marker: F11=BUTTON_A!15 (or legacy BUTTON_A!)
                    hz = 11;
                    String rs = tv.substring(bang + 1).trim();
                    if (!rs.isEmpty()) {
                        try { hz = Integer.parseInt(rs); }
                        catch (NumberFormatException ignored) { }
                    }
                    tv = tv.substring(0, bang);
                }
                int dst = keyCodeFromName(tv);
                if (dst != 0) {
                    m.put(src, dst);
                    Map<Integer, Integer> tm = section.isEmpty() ? gRate
                            : sRate.computeIfAbsent(section, k -> new HashMap<>());
                    if (hz > 0) tm.put(src, hz); else tm.remove(src);
                }
            }
        }
        synchronized (effective) {
            globalMap.clear(); globalMap.putAll(g);
            appSections.clear(); appSections.putAll(secs);
            globalRate.clear(); globalRate.putAll(gRate);
            appRateSections.clear(); appRateSections.putAll(sRate);
        }
        rebuildEffective();
        Log.i(TAG, "config reloaded: " + g.size() + " global entries, fg=" + fgPkg);
    }

    private void rebuildEffective() {
        Map<Integer, Integer> merged = new HashMap<>();
        Map<Integer, Integer> mergedRate = new HashMap<>();
        synchronized (effective) {
            merged.putAll(globalMap);
            mergedRate.putAll(globalRate);
            Map<Integer, Integer> app = appSections.get(fgPkg);
            if (app != null) merged.putAll(app);
            Map<Integer, Integer> appT = appRateSections.get(fgPkg);
            if (appT != null) mergedRate.putAll(appT);
            merged.values().removeIf(v -> v == null); // "-" pass-through markers
            effective.clear(); effective.putAll(merged);
            effectiveRate.clear(); effectiveRate.putAll(mergedRate);
        }
    }

    @Override
    public void onInterrupt() {
        synchronized (held) { held.clear(); }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sConnected = false;
        // safety: release anything held
        synchronized (held) { held.clear(); }
        return super.onUnbind(intent);
    }

    /** "SYSRQ"->KEYCODE_SYSRQ, "A"->KEYCODE_A, "BUTTON_A"->KEYCODE_BUTTON_A ... */
    static int keyCodeFromName(String name) {
        if (name.isEmpty()) return 0;
        try {
            int c = Integer.parseInt(name); // raw numeric form, e.g. "751"
            if (c > 0 && c < 1000) return c;
        } catch (NumberFormatException ignored) {
        }
        String n = name.startsWith("KEYCODE_") ? name : "KEYCODE_" + name;
        try {
            java.lang.reflect.Field f = KeyEvent.class.getField(n);
            return f.getInt(null);
        } catch (Exception e) {
            // @hide keycodes not reachable via reflection on this ROM
            if (n.equals("KEYCODE_SNAPSHOT")) return 299;
            Log.w(TAG, "unknown keycode: " + name);
            return 0;
        }
    }
}
