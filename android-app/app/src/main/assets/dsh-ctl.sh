#!/data/data/com.termux/files/usr/bin/bash
# dsh-ctl.sh — DSH 控制脚本（供「DSH 控制台」App 调用）
# 用法: bash ~/dsh/dsh-ctl.sh {status|start|stop|open|install|log|preflight}
BASE="$HOME/dsh"
URLFILE="$BASE/dsh-web-url.txt"
WDPID="$BASE/dsh-watchdog.pid"
INSTLOG="$BASE/install.log"
PORT=3080
CTL_VER=5

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

repair)
  if [ ! -s "$BASE/dsh-oneclick.sh" ]; then echo "✗ 未找到 dsh-oneclick.sh，请先用「安装」"; exit 1; fi
  echo "==> 快速修复：跳过 npm 安装，重打 Android 兼容补丁"
  [ -f "$INSTLOG" ] && mv -f "$INSTLOG" "$INSTLOG.old" 2>/dev/null
  : > "$INSTLOG"
  setsid bash "$BASE/dsh-oneclick.sh" --skip-npm >> "$INSTLOG" 2>&1 < /dev/null &
  echo $! > "$BASE/install.pid"
  echo "修复已在后台启动（PID $(cat "$BASE/install.pid")）"
  echo "日志: $INSTLOG"
  ;;

uninstall)
  echo "==> 停止服务"
  wd_up && kill "$(cat "$WDPID")" 2>/dev/null && echo "  看门狗已停"
  pkill -f "expose-internal[s]" 2>/dev/null && echo "  dsh web 已停" || echo "  dsh web 未运行"
  rm -f "$URLFILE" "$WDPID"
  echo "==> 移除部署脚本"
  rm -f "$BASE/dsh-oneclick.sh" && echo "  ~/dsh/dsh-oneclick.sh 已删除"
  echo "  已保留：dsh-ctl.sh / dsh-watchdog.sh / 配置 / 日志"
  echo "✅ 卸载完成（dsh 本体仍在，如需彻底移除请选「连 npm 包一起卸载」）"
  ;;

uninstall-npm)
  echo "==> 停止服务"
  wd_up && kill "$(cat "$WDPID")" 2>/dev/null
  pkill -f "expose-internal[s]" 2>/dev/null
  rm -f "$URLFILE" "$WDPID"
  echo "==> 移除部署脚本"
  rm -f "$BASE/dsh-oneclick.sh"
  echo "==> 卸载 npm 全局包"
  if command -v npm >/dev/null 2>&1; then
    npm uninstall -g @deepseek-ai/dsh 2>&1 | tail -5 | sed 's/^/  /'
  fi
  echo "✅ 已彻底卸载 @deepseek-ai/dsh"
  ;;

setkey)
  KEY=""; read -r KEY 2>/dev/null || true
  KEY="$(printf '%s' "$KEY" | tr -d '\r\n')"
  if [ -z "$KEY" ]; then echo "✗ 未收到密钥"; exit 1; fi
  CF="$HOME/.dsh/.credentials.yaml"; mkdir -p "$HOME/.dsh"
  [ -f "$CF" ] || printf 'version: 1\nrefs: {}\n' > "$CF"
  printf '%s' "$KEY" > "$BASE/.apikey"; chmod 600 "$BASE/.apikey"
  python3 - "$CF" "$KEY" <<'PYK'
import re, sys
p, k = sys.argv[1], sys.argv[2]
s = open(p).read()
if "DEEPSEEK_API_KEY:" in s:
    s = re.sub(r'(?m)^(\s*DEEPSEEK_API_KEY:\s*).*$', lambda m: m.group(1) + k, s)
elif re.search(r'(?m)^refs:\s*$', s):
    s = re.sub(r'(?m)^refs:\s*$', 'refs:\n  DEEPSEEK_API_KEY: ' + k, s, count=1)
else:
    s = s.rstrip("\n") + "\nrefs:\n  DEEPSEEK_API_KEY: " + k + "\n"
open(p, "w").write(s)
PYK
  chmod 600 "$CF"
  echo "✅ API Key 已更新（$(printf '%s' "$KEY" | cut -c1-6)…）"
  ;;

clearkey)
  rm -f "$BASE/.apikey"
  CF="$HOME/.dsh/.credentials.yaml"
  if [ -f "$CF" ]; then
    python3 - "$CF" <<'PYK'
import re, sys
p = sys.argv[1]; s = open(p).read()
s = re.sub(r'(?m)^\s*DEEPSEEK_API_KEY:.*\n?', '', s)
open(p, "w").write(s)
PYK
    chmod 600 "$CF"
  fi
  echo "✅ API Key 已清除"
  ;;

tail)
  F="${2:-dsh-web.log}"; N="${3:-3000}"
  case "$F" in
    dsh-web.log|dsh-watchdog.log|install.log|install.log.old|dsh-boot.log|reinstall.log|fix.log) ;;
    *) echo "✗ 不允许查看: $F"; exit 1 ;;
  esac
  if [ ! -f "$BASE/$F" ]; then echo "（$F 不存在）"; exit 0; fi
  echo "===== $F  （末尾 $N 字节）====="
  tail -c "$N" "$BASE/$F" | sed 's/\x1b\[[0-9;]*m//g'
  ;;

log)
  N="${2:-3000}"
  if [ -f "$INSTLOG" ]; then tail -c "$N" "$INSTLOG" | sed 's/\x1b\[[0-9;]*m//g'; else echo "(暂无日志)"; fi
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
