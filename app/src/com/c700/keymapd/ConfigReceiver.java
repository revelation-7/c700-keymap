package com.c700.keymapd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 供 Magisk 模块开机播种配置：
 *   am broadcast -n com.c700.keymapd/.ConfigReceiver -a com.c700.keymapd.SET_CONFIG --es config "F10=SYSRQ;F11=..."
 * 换行与 ';' 都当作分隔符；服务通过 OnSharedPreferenceChangeListener 立即生效。
 */
public class ConfigReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String cfg = intent.getStringExtra("config");
        if (cfg == null) cfg = "";
        cfg = cfg.replace("%0A", "\n").replace("\\n", "\n");
        if (cfg.indexOf('\n') < 0 && cfg.indexOf(';') >= 0) cfg = cfg.replace(';', '\n');
        ctx.getSharedPreferences(RemapService.PREFS, Context.MODE_PRIVATE)
                .edit().putString(RemapService.PREF_RAW, cfg).apply();
        Log.i(RemapService.TAG, "config set via broadcast (" + cfg.length() + " bytes)");
    }
}
