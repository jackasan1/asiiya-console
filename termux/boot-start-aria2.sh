#!/data/data/com.termux/files/usr/bin/sh
# Aria2 离线下载开机自启（由 Termux:Boot 触发）
export PATH=/data/data/com.termux/files/usr/bin:$PATH
termux-wake-lock 2>/dev/null
sleep 5
bash "$HOME/dsh/aria2-ctl.sh" ensure
exit 0
