#!/system/bin/sh
# c700-keymap injector: reads "<keycode> [duration]" lines on stdin,
# injects via `input keyevent` (this ROM ignores -d for keyevents).
# Spawned per-connection by `nc -4 -L -p 57521`.
while read code _rest; do
  case "$code" in ''|*[!0-9]*) continue ;; esac
  if [ "$code" -ge 1 ] && [ "$code" -le 1000 ]; then
    input keyevent "$code" >/dev/null 2>&1
  fi
done
