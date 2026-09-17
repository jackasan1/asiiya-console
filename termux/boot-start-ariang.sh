#!/data/data/com.termux/files/usr/bin/sh
# AriaNg 网页控制台开机自启（纯静态，仅本机 8090）
export PATH=/data/data/com.termux/files/usr/bin:$PATH
termux-wake-lock 2>/dev/null
sleep 6
bash "$HOME/dsh/ariang-ctl.sh" ensure
exit 0
