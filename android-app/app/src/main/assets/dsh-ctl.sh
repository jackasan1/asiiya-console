#!/data/data/com.termux/files/usr/bin/bash
# dsh-ctl.sh — DSH 控制脚本（供「DSH 控制台」App 调用）
# 用法: bash ~/dsh/dsh-ctl.sh {status|start|stop|open|install|log|preflight|cost|openlist-*}
BASE="$HOME/dsh"
URLFILE="$BASE/dsh-web-url.txt"
WDPID="$BASE/dsh-watchdog.pid"
INSTLOG="$BASE/install.log"
[ -f "$BASE/config.sh" ] && . "$BASE/config.sh"
PORT="${DSH_PORT:-3080}"
CTL_VER=7

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


# ================= OpenList（原 AList）辅助 =================
: "${PREFIX:=/data/data/com.termux/files/usr}"
OL_PORT=5244
OL_SVDIR="$PREFIX/var/service/openlist"
OL_DATA="$HOME/.local/share/openlist"
OL_BOOTF="$HOME/.termux/boot/start-openlist.sh"
OL_VERF="$BASE/.olversion"

ol_bin()  { command -v openlist 2>/dev/null || command -v alist 2>/dev/null; }
ol_inst() { [ -n "$(ol_bin)" ]; }
ol_code() { curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://127.0.0.1:$OL_PORT/" 2>/dev/null; }
ol_ready(){ local c; c="$(ol_code)"; [ "$c" = "200" ] || [ "$c" = "401" ]; }
ol_run()  { sv status "$OL_SVDIR" 2>/dev/null | grep -q '^run:'; }
ol_pid()  {
  local x p d; x="$(ol_bin)"; [ -n "$x" ] || return 0
  if command -v pidof >/dev/null 2>&1; then
    p="$(pidof "$x" 2>/dev/null | tr ' ' '\n' | head -1)"
    [ -n "$p" ] && { printf '%s' "$p"; return 0; }
  fi
  for d in /proc/[0-9]*; do
    [ -r "$d/comm" ] || continue
    [ "$(cat "$d/comm" 2>/dev/null)" = "$x" ] && { printf '%s' "${d#/proc/}"; return 0; }
  done
  return 0
}
ol_boot() { [ -f "$OL_BOOTF" ] && printf 'ON' || printf 'OFF'; }
ol_ver()  {
  if [ -s "$OL_VERF" ]; then cat "$OL_VERF"; return; fi
  local v; v="$(dpkg-query -W -f='${Version}' openlist 2>/dev/null)"
  [ -n "$v" ] && [ "$v" != "(none)" ] && printf '%s' "$v" > "$OL_VERF"
  printf '%s' "$v"
}
ol_rt()   {
  local p es h m s; p="$(ol_pid)"; [ -n "$p" ] || return 0
  es="$(ps -o etimes= -p "$p" 2>/dev/null | tr -d ' ')"
  case "$es" in ''|*[!0-9]*) return 0;; esac
  h=$((es / 3600)); m=$((es % 3600 / 60)); s=$((es % 60))
  printf '%02d:%02d:%02d' "$h" "$m" "$s"
}
ensure_sv() {
  local pf="$PREFIX/var/run/service-daemon.pid"
  if [ -f "$pf" ] && kill -0 "$(cat "$pf" 2>/dev/null)" 2>/dev/null; then return 0; fi
  rm -f "$pf"
  command -v service-daemon >/dev/null 2>&1 || return 1
  service-daemon start >/dev/null 2>&1
  local i; for i in $(seq 1 15); do [ -d "$OL_SVDIR/supervise" ] && return 0; sleep 1; done
  return 1
}
ol_wait() { local i; for i in $(seq 1 30); do ol_ready && return 0; sleep 1; done; return 1; }
ol_write_run() {
  mkdir -p "$OL_SVDIR"
  cat > "$OL_SVDIR/run" <<'OLRUN'
#!/data/data/com.termux/files/usr/bin/sh
cd /data/data/com.termux/files/home/.local/share/openlist || exit 1
exec openlist server --data /data/data/com.termux/files/home/.local/share/openlist 2>&1
OLRUN
  chmod +x "$OL_SVDIR/run"
}
ol_fix_conf() {
  mkdir -p "$OL_DATA/temp" "$OL_DATA/log"
  [ -f "$OL_DATA/config.json" ] || return 0
  python3 - "$OL_DATA" <<'OLPY'
import json, os, sys
d = sys.argv[1]
f = os.path.join(d, "config.json")
c = json.load(open(f))
c.setdefault("database", {})["db_file"] = os.path.join(d, "data.db")
c["temp_dir"]  = os.path.join(d, "temp")
c["bleve_dir"] = os.path.join(d, "bleve")
c.setdefault("log", {})["name"] = os.path.join(d, "log", "log.log")
json.dump(c, open(f, "w"), indent=2)
OLPY
}
ol_write_boot() {
  mkdir -p "$HOME/.termux/boot"
  cat > "$OL_BOOTF" <<'OLBOOT'
#!/data/data/com.termux/files/usr/bin/sh
# OpenList（原 AList）开机自启 — 由 Termux:Boot 触发
PREFIX=/data/data/com.termux/files/usr
export SVDIR=$PREFIX/var/service
export PATH=$PREFIX/bin:$PATH
termux-wake-lock 2>/dev/null
PIDFILE=$PREFIX/var/run/service-daemon.pid
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then :; else
  rm -f "$PIDFILE"; service-daemon start
fi
sleep 3
sv up openlist 2>/dev/null
exit 0
OLBOOT
  chmod +x "$OL_BOOTF"
}
# =============== OpenList 辅助结束 ===============

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
  MN="$(grep -A4 '^agent-default-model:' "$HOME/.dsh/settings.yaml" 2>/dev/null | grep -m1 -E '^[[:space:]]+model:' | sed 's/.*model:[[:space:]]*//')"
  MD=MISSING
  if [ -s "$HOME/.dsh/.credentials.yaml" ] && grep -q "DEEPSEEK_API_KEY" "$HOME/.dsh/.credentials.yaml" 2>/dev/null; then MD=OK; fi
  OLST=DOWN; ol_run && OLST=UP
  OLCODE="$(ol_code)"
  OLPID="$(ol_pid)"
  OLRT="$(ol_rt)"
  OLV="$(ol_ver)"
  OLBOOT="$(ol_boot)"
  OLINST=0; ol_inst && OLINST=1
  printf '{"service":"%s","watchdog":"%s","port":"%s","portCode":"%s","dshVersion":"%s","install":"%s","ctlVersion":"%s","url":"%s","pid":"%s","procs":"%s","runtime":"%s","model":"%s","modelName":"%s","olState":"%s","olPortCode":"%s","olPort":"%s","olUrl":"%s","olPid":"%s","olRuntime":"%s","olVersion":"%s","olBoot":"%s","olInstalled":"%s"}\n' \
    "$S" "$W" "$P" "$(port_code)" "$(jesc "$V")" "$I" "$CTL_VER" "$(jesc "$(ensure_url)")" "$PID" "$NP" "$RT" "$MD" "$(jesc "$MN")" "$OLST" "$OLCODE" "$OL_PORT" "$(jesc "http://127.0.0.1:$OL_PORT")" "$OLPID" "$OLRT" "$(jesc "$OLV")" "$OLBOOT" "$OLINST"
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

setmodel)
  M="${2:-}"
  [ -n "$M" ] || { echo "✗ 未指定模型"; exit 1; }
  SF="$HOME/.dsh/settings.yaml"; mkdir -p "$HOME/.dsh"
  [ -f "$SF" ] || printf 'agent-default-model:\n  provider: deepseek-official\n  model: deepseek-flash\n' > "$SF"
  python3 - "$SF" "$M" <<'PYM'
import sys
p, m = sys.argv[1], sys.argv[2]
lines = open(p).read().split("\n")
done = False
for i, l in enumerate(lines):
    if l.startswith("agent-default-model:"):
        for j in range(i + 1, len(lines)):
            if lines[j] and not lines[j][0].isspace(): break
            if lines[j].strip().startswith("model:"):
                lines[j] = lines[j][:len(lines[j]) - len(lines[j].lstrip())] + "model: " + m
                done = True
                break
        break
if not done:
    lines += ["agent-default-model:", "  provider: deepseek-official", "  model: " + m]
open(p, "w").write("\n".join(lines))
PYM
  echo "✅ 默认模型已设为 $M"
  echo "  重启服务后生效"
  ;;

restart)
  echo "==> 重启服务"
  "$0" stop >/dev/null 2>&1
  sleep 2
  "$0" start
  ;;

checkpatch)
  DSH="$PREFIX/lib/node_modules/@deepseek-ai/dsh"
  N="$DSH/node_modules/@deepseek-ai"
  FE="$N/dsh-web-frontend/dist"
  OK=0; BAD=0
  ck() { if eval "$2" >/dev/null 2>&1; then echo "✅ $1"; OK=$((OK+1)); else echo "✗ $1"; BAD=$((BAD+1)); fi; }
  echo "==> 补丁状态自检"
  ck "flock 原生模块已编译"      "[ -f '$N/node-addon-system/lib/flock-android.node' ]"
  ck "flock.js 含 android 分支"  "grep -q \"platform === 'android'\" '$N/node-addon-system/lib/flock.js'"
  ck "session 持久化 link+rename" "grep -qE '^import \{[^}]*\blink\b' '$N/dsh-session-persistence-jsonl/lib/index.js' && grep -qE '^import \{[^}]*\brename\b' '$N/dsh-session-persistence-jsonl/lib/index.js'"
  ck "权限预设 defaultPreset"     "grep -q defaultPreset '$BASE/profiles/web/cordis.patch.yml' 2>/dev/null || grep -q defaultPreset '$HOME/.dsh/profiles/web/cordis.patch.yml'"
  ck "sharp wasm 兜底"           "ls '$DSH/node_modules/@img/sharp-wasm32'/lib/*.wasm"
  ck "dsh 包装器 expose-internals" "grep -q expose-internals '$PREFIX/bin/dsh'"
  ck "前端手机适配已注入"          "grep -q dsh-mobile-adapt '$FE/index.html'"
  ck "看门狗脚本存在"             "[ -x '$BASE/dsh-watchdog.sh' ]"
  ck "开机自启脚本存在"           "[ -f '$HOME/.termux/boot/start-dsh.sh' ]"
  echo "----"
  echo "通过 $OK 项，失败 $BAD 项"
  [ "$BAD" -eq 0 ] || exit 1
  ;;

checkey)
  CF="$HOME/.dsh/.credentials.yaml"
  K="$(sed -n 's/^[[:space:]]*DEEPSEEK_API_KEY:[[:space:]]*//p' "$CF" 2>/dev/null | head -1)"
  [ -z "$K" ] && [ -f "$BASE/.apikey" ] && K="$(cat "$BASE/.apikey" 2>/dev/null)"
  if [ -z "$K" ]; then echo "✗ 未配置 API Key"; exit 1; fi
  C="$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 https://api.deepseek.com/models -H "Authorization: Bearer $K" 2>/dev/null)"
  if [ "$C" = "200" ]; then echo "✅ API Key 有效（HTTP 200）"; else echo "✗ API Key 无效（HTTP ${C:-超时}）"; exit 1; fi
  ;;

getconf)
  [ -f "$BASE/config.sh" ] && . "$BASE/config.sh"
  BOOT=off; [ -x "$HOME/.termux/boot/start-dsh.sh" ] && BOOT=on
  printf '{"port":"%s","wdInterval":"%s","boot":"%s"}\n' "${DSH_PORT:-3080}" "${WD_INTERVAL:-60}" "$BOOT"
  ;;

setconf)
  key="$(printf '%s' "${2:-}" | tr 'A-Z' 'a-z')"; val="${3:-}"
  case "$key" in
    port)       case "$val" in ''|*[!0-9]*) echo "✗ 端口必须是数字"; exit 1;; esac
                [ "$val" -ge 1024 ] && [ "$val" -le 65535 ] || { echo "✗ 端口范围 1024-65535"; exit 1; }
                line="DSH_PORT=$val";;
    wdinterval) case "$val" in ''|*[!0-9]*) echo "✗ 间隔必须是数字"; exit 1;; esac
                [ "$val" -ge 15 ] && [ "$val" -le 3600 ] || { echo "✗ 间隔范围 15-3600 秒"; exit 1; }
                line="WD_INTERVAL=$val";;
    boot)       [ "$val" = "on" ] || [ "$val" = "off" ] || { echo "✗ 只能是 on / off"; exit 1; }
                if [ "$val" = "on" ]; then chmod +x "$HOME/.termux/boot/start-dsh.sh" 2>/dev/null; else chmod -x "$HOME/.termux/boot/start-dsh.sh" 2>/dev/null; fi
                echo "✅ 开机自启已$([ "$val" = on ] && echo 开启 || echo 关闭)"; exit 0;;
    *)          echo "✗ 未知配置项: $key（可用 port / wdInterval / boot）"; exit 1;;
  esac
  LOWER="$(printf '%s' "$key" | tr 'A-Z' 'a-z')"
  CFG="$BASE/config.sh"; touch "$CFG"
  grep -q "^DSH_PORT=" "$CFG" 2>/dev/null || echo "DSH_PORT=3080" >> "$CFG"
  grep -q "^WD_INTERVAL=" "$CFG" 2>/dev/null || echo "WD_INTERVAL=60" >> "$CFG"
  case "$key" in
    port)       sed -i "s/^DSH_PORT=.*/$line/" "$CFG";;
    wdinterval) sed -i "s/^WD_INTERVAL=.*/$line/" "$CFG";;
  esac
  echo "✅ 已设置 $key = $val"
  ;;

buildtime)
  N="${2:-6}"
  if [ -x "$BASE/build-time.sh" ]; then
    bash "$BASE/build-time.sh" "$N" 2>&1
  else
    echo "✗ 未找到 ~/dsh/build-time.sh"
    echo "  请先运行一次完整安装"
    exit 1
  fi
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
  if ol_inst; then echo "OpenList: 已装 ($(ol_ver)) 自启 $(ol_boot)"; else echo "OpenList: 未安装"; fi
  if ol_run; then echo "OpenList 服务: 运行中"; else echo "OpenList 服务: 已停止"; fi
  echo "ctl 版本: $CTL_VER"
  ;;


openlist-status)
  if ! ol_inst; then echo "✗ OpenList 未安装（可点「安装 OpenList」）"; exit 0; fi
  echo "服务: $(ol_run && echo UP || echo DOWN)    HTTP: $(ol_code)"
  echo "地址: http://127.0.0.1:$OL_PORT"
  echo "PID: $(ol_pid)    运行时长: $(ol_rt)"
  echo "版本: $(ol_ver)    开机自启: $(ol_boot)"
  echo "数据目录: $OL_DATA"
  ;;

openlist-install)
  if ! ol_inst; then
    echo "==> 安装 openlist + termux-services"
    pkg install -y openlist termux-services 2>&1 | tail -10
  else
    echo "==> openlist 已安装（$(ol_ver)），跳过 pkg install"
  fi
  ol_inst || { echo "✗ 安装失败，请检查网络/镜像源"; exit 1; }
  rm -f "$OL_VERF"
  mkdir -p "$OL_DATA"
  ol_fix_conf
  ol_write_run
  if [ ! -s "$OL_DATA/data.db" ]; then
    echo "==> 初始化数据库"
    OLNEW="$(openlist admin random --data "$OL_DATA" 2>/dev/null | sed -n 's/^password: *//p' | tail -1)"
    [ -n "$OLNEW" ] || OLNEW="$(openlist admin --data "$OL_DATA" 2>/dev/null | sed -n 's/.*initial password is: *//p' | tail -1)"
    if [ -n "$OLNEW" ]; then
      echo "──────── 请立刻保存 ────────"
      echo "  用户名: admin"
      echo "  密  码: $OLNEW"
      echo "───────────────────────────"
    else
      echo "⚠ 未取得初始密码，可用「更多 → 重置管理员密码」重新生成"
    fi
  else
    echo "==> 已有数据库，保留现有账号密码"
  fi
  ensure_sv || echo "⚠ 服务监管（runsvdir）未启动"
  sv up "$OL_SVDIR" 2>/dev/null
  if ol_wait; then
    echo "✅ OpenList 已就绪 (HTTP $(ol_code))"
    echo "   地址: http://127.0.0.1:$OL_PORT"
  else
    echo "⚠ 启动超时，请查看日志"
  fi
  ;;

openlist-start)
  ol_inst || { echo "✗ 未安装 OpenList，请先点「安装 OpenList」"; exit 1; }
  ensure_sv || { echo "✗ 服务监管进程启动失败"; exit 1; }
  ol_write_run
  sv up "$OL_SVDIR" 2>/dev/null
  if ol_wait; then
    echo "✅ OpenList 已启动 (HTTP $(ol_code))"
    echo "   地址: http://127.0.0.1:$OL_PORT"
  else
    echo "⚠ 等待超时，请查看日志"
  fi
  ;;

openlist-stop)
  if sv down "$OL_SVDIR" 2>/dev/null; then echo "✅ OpenList 已停止"; else echo "OpenList 未在运行"; fi
  ;;

openlist-restart)
  ol_inst || { echo "✗ 未安装 OpenList"; exit 1; }
  ensure_sv >/dev/null 2>&1
  sv down "$OL_SVDIR" 2>/dev/null
  sleep 2
  ol_write_run
  sv up "$OL_SVDIR" 2>/dev/null
  if ol_wait; then echo "✅ OpenList 已重启 (HTTP $(ol_code))"; else echo "⚠ 重启超时"; fi
  ;;

openlist-passwd)
  ol_inst || { echo "✗ 未安装 OpenList"; exit 1; }
  OLPW="$(cat | tr -d '\r\n')"
  [ -n "$OLPW" ] || { echo "✗ 未收到新密码"; exit 1; }
  OLOUT="$(openlist admin set "$OLPW" --data "$OL_DATA" 2>&1)"
  if printf '%s' "$OLOUT" | grep -qi 'updated'; then
    echo "✅ 管理员密码已更新（用户名 admin）"
  else
    echo "✗ 修改失败：$(printf '%s' "$OLOUT" | tail -2)"
    exit 1
  fi
  ;;

openlist-passwd-random)
  ol_inst || { echo "✗ 未安装 OpenList"; exit 1; }
  OLOUT="$(openlist admin random --data "$OL_DATA" 2>&1)"
  OLPW="$(printf '%s' "$OLOUT" | sed -n 's/^password: *//p' | tail -1)"
  if [ -n "$OLPW" ]; then
    echo "✅ 已重置为随机密码"
    echo "──────── 请立刻保存 ────────"
    echo "  用户名: admin"
    echo "  密  码: $OLPW"
    echo "───────────────────────────"
  else
    echo "✗ 重置失败：$(printf '%s' "$OLOUT" | tail -2)"; exit 1
  fi
  ;;

openlist-boot)
  case "${2:-}" in
    on)
      ol_write_boot
      echo "✅ OpenList 开机自启已开启"
      echo "   ⚠ 需已安装 Termux:Boot 且手动打开过它一次"
      ;;
    off)
      rm -f "$OL_BOOTF"
      echo "✅ OpenList 开机自启已关闭"
      ;;
    *) echo "用法: openlist-boot {on|off}"; exit 1;;
  esac
  ;;

openlist-log)
  OLN="${2:-3000}"
  OLF="$OL_DATA/log/log.log"
  if [ -f "$OLF" ]; then
    tail -c "$OLN" "$OLF" | sed 's/\x1b\[[0-9;]*m//g'
  else
    echo "（暂无日志：$OLF）"
  fi
  ;;

cost)
  if [ -x "$BASE/dsh-cost.sh" ]; then exec bash "$BASE/dsh-cost.sh" "${@:2}"
  else echo "✗ 未找到 ~/dsh/dsh-cost.sh（先在 App 里刷新一次状态即可落盘）"; exit 1; fi
  ;;

*)
  echo "用法: dsh-ctl.sh {status|start|stop|open|install|log|preflight|cost|openlist-*}"
  exit 1
  ;;
esac
