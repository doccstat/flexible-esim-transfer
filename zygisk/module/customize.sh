#!/system/bin/sh
[ "$BOOTMODE" = true ] || abort 'Install from your root manager, not recovery.'
case "$ARCH" in
  arm64|arm|x64|x86) ;;
  *) abort 'Unsupported architecture.' ;;
esac
[ "$API" -ge 33 ] || abort 'Android 13 or later is required.'
STATE=/data/adb/flexible-esim-transfer
if [ -L "$STATE" ]; then abort 'State directory must not be a symlink.'; fi
mkdir -p "$STATE" || abort 'Cannot create private state.'
chmod 700 "$STATE"
if [ ! -e "$STATE/config" ]; then
  (umask 077; printf 'FET1\n\n\n0\n' > "$STATE/config") || abort 'Cannot initialize settings.'
fi
set_perm "$MODPATH/ctl.sh" 0 0 0700
ui_print 'Standalone Zygisk backend installed. Requires an active Zygisk provider.'
ui_print 'Use WebUI to select the destination. Routing starts off on a fresh install.'
ui_print 'Disable the Vector version before activating this backend.'
ui_print 'No app, radio, framework or device restart is performed by this installer.'
