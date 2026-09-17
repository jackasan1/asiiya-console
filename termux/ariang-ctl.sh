#!/data/data/com.termux/files/usr/bin/bash
# ariang-ctl.sh — AriaNg 网页控制台（纯静态，仅本机）
PORT=8090; DIR="$HOME/aria-ng"; PIDF="$HOME/dsh/ariang.pid"; LOG="$HOME/dsh/ariang.log"
up()   { curl -s -o /dev/null --max-time 2 "http://127.0.0.1:$PORT/" ; [ $? -eq 0 ]; }
case "${1:-status}" in
start)
  up && { echo "已在运行"; exit 0; }
  [ -f "$DIR/index.html" ] || { echo "✗ 缺 $DIR/index.html"; exit 1; }
  cd "$DIR" && nohup python3 -m http.server $PORT --bind 127.0.0.1 >>"$LOG" 2>&1 &
  echo $! > "$PIDF"; sleep 1; up && echo "✅ AriaNg 已启动 :$PORT" || echo "✗ 启动失败";;
stop) [ -f "$PIDF" ] && kill "$(cat "$PIDF")" 2>/dev/null; pkill -f "http.server $PORT" 2>/dev/null; rm -f "$PIDF"; echo "已停止";;
restart) bash "$0" stop >/dev/null; sleep 1; bash "$0" start;;
ensure) up || bash "$0" start >/dev/null 2>&1;;
status) up && echo "运行中 :$PORT" || echo "未运行";;
url) cat "$HOME/dsh/ariang-url.txt";;
*) echo "用法: ariang-ctl.sh {start|stop|restart|status|ensure|url}";;
esac
