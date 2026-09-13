#!/system/bin/sh
# c700-keymap v2 assembler (root convenience; app core works without root for screenshot maps).
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
sleep 8
MOD=/data/adb/modules/c700-keymap
DIR=/data/adb/c700-keymap
mkdir -p "$DIR"

# 1) install the bundled app only when it is missing or changed.
#    (re-installing on every boot is unnecessary and can leave the system's
#     accessibility key filter un-armed for that session)
STAMP="$DIR/app.stamp"
NEW=$(md5sum "$MOD/app.apk" 2>/dev/null | cut -d' ' -f1)
OLD=$(cat "$STAMP" 2>/dev/null)
if ! pm path com.c700.keymapd >/dev/null 2>&1 || [ -z "$NEW" ] || [ "$NEW" != "$OLD" ]; then
  if pm install -r "$MOD/app.apk" >/dev/null 2>&1; then
    echo "$NEW" > "$STAMP"
  fi
fi

# 2) injector: resident root daemon (low latency); nc+sh fallback if it fails
cp -f "$MOD/inject.dex" "$DIR/inject.dex" 2>/dev/null
cp -f "$MOD/injectd-handler.sh" "$DIR/injectd-handler.sh"
chmod 755 "$DIR/injectd-handler.sh"
if ! pgrep -f InjectDaemon >/dev/null && ! pgrep -f "nc -4 -L -p 57521" >/dev/null; then
  setsid app_process -Djava.class.path="$DIR/inject.dex" /system/bin \
    com.c700.keymapd.InjectDaemon 57521 >>"$DIR/inject.log" 2>&1 </dev/null &
  sleep 2
  pgrep -f InjectDaemon >/dev/null || \
    setsid nc -4 -L -p 57521 sh "$DIR/injectd-handler.sh" >/dev/null 2>&1 </dev/null &
fi

# 3) enable our accessibility service first in the list (we must consume keys
#    before the vendor service), preserving existing entries
CUR=$(settings get secure enabled_accessibility_services)
case "$CUR" in
  "com.c700.keymapd/com.c700.keymapd.RemapService"*) : ;;
  *com.c700.keymapd/*)
    rest=$(echo "$CUR" | sed 's|com.c700.keymapd/com.c700.keymapd.RemapService:||; s|:com.c700.keymapd/com.c700.keymapd.RemapService||')
    settings put secure enabled_accessibility_services "com.c700.keymapd/com.c700.keymapd.RemapService${rest:+:$rest}" ;;
  "") settings put secure enabled_accessibility_services "com.c700.keymapd/com.c700.keymapd.RemapService" ;;
  *)  settings put secure enabled_accessibility_services "com.c700.keymapd/com.c700.keymapd.RemapService:$CUR" ;;
esac
settings put secure accessibility_enabled 1

# 4) seed app config ONLY if the app has none yet, and ONLY after the
#    user has unlocked CE storage (otherwise prefs are invisible at boot
#    and we would wrongly re-seed over the user's config every time)
(
  i=0
  while [ "$i" -lt 90 ]; do
    ls /storage/emulated/0/Android/data >/dev/null 2>&1 && break
    i=$((i+1)); sleep 2
  done
  PXML=/data/data/com.c700.keymapd/shared_prefs/keymap.xml
  if ! (ls /data/data/com.c700.keymapd >/dev/null 2>&1 && \
        grep -q 'name="raw">[^<]' "$PXML" 2>/dev/null); then
    CONF=$DIR/config
    [ -f "$CONF" ] || cp "$MOD/config.default" "$CONF" 2>/dev/null
    CFG=$(tr '\n' ';' < "$CONF" 2>/dev/null)
    [ -n "$CFG" ] && am broadcast -n com.c700.keymapd/.ConfigReceiver -a com.c700.keymapd.SET_CONFIG --es config "$CFG" >/dev/null 2>&1
  fi
) &
