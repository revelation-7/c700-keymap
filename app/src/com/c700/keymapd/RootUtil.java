package com.c700.keymapd;

import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/** Minimal root helper: run a batch of commands through su. */
public final class RootUtil {

    private RootUtil() {
    }

    public static boolean su(String... cmds) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            OutputStream os = p.getOutputStream();
            for (String c : cmds) os.write((c + "\n").getBytes("UTF-8"));
            os.write("exit\n".getBytes());
            os.flush();
            os.close();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static String suOut(String... cmds) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            OutputStream os = p.getOutputStream();
            for (String c : cmds) os.write((c + "\n").getBytes("UTF-8"));
            os.write("exit\n".getBytes());
            os.flush();
            os.close();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = p.getInputStream().read(buf)) > 0) bos.write(buf, 0, n);
            p.waitFor(10, TimeUnit.SECONDS);
            return new String(bos.toByteArray(), "UTF-8").trim();
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Make our a11y service FIRST in the enabled list, preserving others. */
    public static boolean enableA11y(String ours, String currentSecure) {
        StringBuilder sb = new StringBuilder(ours);
        if (currentSecure != null) {
            for (String part : currentSecure.split(":")) {
                if (!part.trim().isEmpty() && !part.equals(ours)) sb.append(':').append(part);
            }
        }
        return su(
                "settings put secure enabled_accessibility_services '" + sb + "'",
                "settings put secure accessibility_enabled 1");
    }

    /** Remove our component from the enabled a11y list (keeps others). */
    public static boolean disableA11y(String ours, String currentSecure) {
        StringBuilder sb = new StringBuilder();
        if (currentSecure != null) {
            for (String part : currentSecure.split(":")) {
                String p = part.trim();
                if (p.isEmpty() || p.equals(ours)) continue;
                if (sb.length() > 0) sb.append(':');
                sb.append(p);
            }
        }
        if (sb.length() == 0) {
            return su("settings put secure enabled_accessibility_services ''",
                    "settings put secure accessibility_enabled 0");
        }
        return su("settings put secure enabled_accessibility_services '" + sb + "'");
    }

    public static boolean moduleEnabled() {
        String o = suOut("test -f /data/adb/modules/c700-keymap/disable "
                + "&& echo yes || echo no");
        return !o.contains("yes");
    }

    /** Enable/disable the Magisk module via its disable flag (takes effect on boot). */
    public static boolean setModuleEnabled(boolean enabled) {
        if (enabled) return su("rm -f /data/adb/modules/c700-keymap/disable");
        return su("touch /data/adb/modules/c700-keymap/disable");
    }

    public static boolean startDaemon() {
        return su(
                "pgrep -f InjectDaemon >/dev/null || setsid app_process "
                        + "-Djava.class.path=/data/adb/c700-keymap/inject.dex "
                        + "/system/bin com.c700.keymapd.InjectDaemon 57521 "
                        + ">>/data/adb/c700-keymap/inject.log 2>&1 </dev/null &");
    }
}
