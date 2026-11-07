#!/system/bin/sh
set -eu
[ "$(id -u)" = 0 ] || exit 1
[ "$#" = 1 ] || exit 1
case "$1" in status|inventory|save|off|start|stop|delete|review|export) ;; *) exit 1 ;; esac
MODDIR=${0%/*}
umask 077
export CLASSPATH="$MODDIR/payload.jar"
exec /system/bin/toybox timeout -s TERM 15 /system/bin/app_process /system/bin com.lixingchi.flexibleesimtransfer.ModuleControl "$1"
