#!/data/data/com.termux/files/usr/bin/bash
# dsh-ctl.sh — DSH 控制脚本（供「DSH 控制台」App 调用）
# 用法: bash ~/dsh/dsh-ctl.sh {status|start|stop|open|install|log|preflight}
BASE="$HOME/dsh"
URLFILE="$BASE/dsh-web-url.txt"
WDPID="$BASE/dsh-watchdog.pid"
INSTLOG="$BASE/install.log"
PORT=3080
CTL_VER=4

jesc() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
port_code() { local c; c="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://127.0.0.1:$PORT/" 2>/dev/null)"; printf '%s' "${c:-000}"; }
svc_up() { pgrep -f "expose-internals" >/dev/null 2>&1; }
wd_up()  { [ -f "$WDPID" ] && kill -0 "$(cat "$WDPID" 2>/dev/null)" 2>/dev/null; }
cur_url(){ sed -n 's/^dsh web: //p' "$URLFILE" 2>/dev/null | head -1; }
ready()  { local c; c="$(port_code)"; [ "$c" = "200" ] || [ "$c" = "401" ]; }
ensure_url() {
  local u; u="$(cur_url)"
  if [ -z "$u" ] && [ -f "$BASE/dsh-web.log" ]; then
    u="$(grep -o 'http://127\.0\.0\.1:3080/?token=[A-Za-z0-9_-]*' "$BASE/dsh-web.log" 2>/dev/null | tail -1)"
    [ -n "$u" ] && printf 'dsh web: %s\n' "$u" > "$URLFILE"
  fi
  printf '%s' "$u"
}
wait_ready() {
  local i; for i in $(seq 1 45); do ready && return 0; sleep 2; done; return 1
}

case "${1:-status}" in

status)
  S=DOWN; svc_up && S=UP
  W=DOWN; wd_up && W=UP
  P=DOWN; ready && P=UP
  V=""; command -v dsh >/dev/null 2>&1 && V="$(dsh --version 2>/dev/null | head -1)"
  I=IDLE; [ -f "$BASE/install.pid" ] && kill -0 "$(cat "$BASE/install.pid" 2>/dev/null)" 2>/dev/null && I=RUNNING
  PID="$(pgrep -f "expose-internals" 2>/dev/null | head -1)"
  NP="$(pgrep -fc "expose-internals" 2>/dev/null)"; [ -n "$NP" ] || NP=0
  RT=""
  if [ -n "$PID" ]; then
    ES="$(ps -o etimes= -p "$PID" 2>/dev/null | tr -d ' ')"
    if [ -n "$ES" ] && [ "$ES" -eq "$ES" ] 2>/dev/null; then
      H=$((ES / 3600)); M=$((ES % 3600 / 60)); S2=$((ES % 60))
      RT="$(printf '%02d:%02d:%02d' "$H" "$M" "$S2")"
    fi
  fi
  MD=MISSING
  if [ -s "$HOME/.dsh/.credentials.yaml" ] && grep -q "DEEPSEEK_API_KEY" "$HOME/.dsh/.credentials.yaml" 2>/dev/null; then MD=OK; fi
  printf '{"service":"%s","watchdog":"%s","port":"%s","portCode":"%s","dshVersion":"%s","install":"%s","ctlVersion":"%s","url":"%s","pid":"%s","procs":"%s","runtime":"%s","model":"%s"}\n' \
    "$S" "$W" "$P" "$(port_code)" "$(jesc "$V")" "$I" "$CTL_VER" "$(jesc "$(ensure_url)")" "$PID" "$NP" "$RT" "$MD"
  ;;

start)
  if wd_up; then echo "看门狗已在运行"; else
    setsid bash "$BASE/dsh-watchdog.sh" >/dev/null 2>&1 < /dev/null &
    echo "看门狗已拉起"
  fi
  if wait_ready; then echo "服务就绪（HTTP $(port_code)）"; else echo "等待超时，请查看日志"; fi
  for i in $(seq 1 30); do [ -n "$(ensure_url)" ] && break; sleep 2; done
  echo "URL=$(ensure_url)"
  ;;

stop)
  if wd_up; then kill "$(cat "$WDPID")" 2>/dev/null && echo "看门狗已停"; else echo "看门狗未在运行"; fi
  sleep 1
  if pkill -f "expose-internal[s]" 2>/dev/null; then echo "dsh web 已停"; else echo "dsh web 未在运行"; fi
  sleep 2
  if svc_up; then echo "仍有残留进程"; else echo "已全部停止"; fi
  rm -f "$URLFILE"
  ;;

open)
  if ! ready; then
    wd_up || setsid bash "$BASE/dsh-watchdog.sh" >/dev/null 2>&1 < /dev/null &
    wait_ready || { echo "服务未就绪，无法打开"; exit 1; }
  fi
  for i in $(seq 1 15); do [ -n "$(ensure_url)" ] && break; sleep 2; done
  U="$(ensure_url)"
  if [ -z "$U" ]; then echo "未取到地址（服务可能在启动中，稍后重试）"; exit 1; fi
  echo "URL=$U"
  ;;

install)
  KEY=""; read -r KEY 2>/dev/null || true
  if [ -n "$KEY" ]; then printf '%s' "$KEY" > "$BASE/.apikey"; chmod 600 "$BASE/.apikey"; fi
  if [ -f "$INSTLOG" ]; then mv -f "$INSTLOG" "$INSTLOG.old" 2>/dev/null; fi
  : > "$INSTLOG"
  if [ -s "$BASE/.apikey" ]; then
    DSH_API_KEY="$(cat "$BASE/.apikey")" setsid bash "$BASE/dsh-oneclick.sh" >> "$INSTLOG" 2>&1 < /dev/null &
  else
    setsid bash "$BASE/dsh-oneclick.sh" >> "$INSTLOG" 2>&1 < /dev/null &
  fi
  echo $! > "$BASE/install.pid"
  echo "安装已在后台启动（PID $(cat "$BASE/install.pid")）"
  echo "日志: $INSTLOG"
  ;;

log)
  N="${2:-3000}"
  if [ -f "$INSTLOG" ]; then tail -c "$N" "$INSTLOG"; else echo "(暂无日志)"; fi
  ;;

preflight)
  echo "HOME=$HOME"
  command -v bash >/dev/null && echo "bash: OK" || echo "bash: 缺失"
  command -v node >/dev/null && echo "node: $(node -v)" || echo "node: 缺失"
  command -v dsh  >/dev/null && echo "dsh: $(dsh --version 2>/dev/null | head -1)" || echo "dsh: 未安装"
  [ -d "$BASE" ] && echo "~/dsh: 存在" || echo "~/dsh: 不存在"
  grep -q 'allow-external-apps=true' "$HOME/.termux/termux.properties" 2>/dev/null \
    && echo "allow-external-apps: OK" || echo "allow-external-apps: 未设置 ← 需要修复"
  command -v termux-battery-status >/dev/null && echo "termux-api 脚本: OK" || echo "termux-api 脚本: 缺失"
  [ -f "$BASE/dsh-watchdog.sh" ] && echo "看门狗脚本: OK" || echo "看门狗脚本: 缺失"
  [ -f "$HOME/.termux/boot/start-dsh.sh" ] && echo "开机自启: OK" || echo "开机自启: 缺失"
  echo "ctl 版本: $CTL_VER"
  ;;

*)
  echo "用法: dsh-ctl.sh {status|start|stop|open|install|log|preflight}"
  exit 1
  ;;
esac
