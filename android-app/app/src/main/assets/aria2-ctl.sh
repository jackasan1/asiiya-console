#!/data/data/com.termux/files/usr/bin/bash
# aria2-ctl.sh — Aria2 离线下载控制（供「Asiiya 工作台」App 调用）
#   start / stop / restart / status / json / ensure / log / secret / info
BASE="$HOME/dsh"
CONF="$HOME/.aria2/aria2.conf"
LOG="$BASE/aria2.log"
RPC="http://127.0.0.1:6800/jsonrpc"
PORT=6800
SECRET="$(sed -n 's/^rpc-secret=//p' "$CONF" 2>/dev/null | head -1)"

rpc() {  # rpc <method> [extraParamsJson]
  curl -s --max-time 3 -H 'Content-Type: application/json' \
    -d "{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"$1\",\"params\":[\"token:$SECRET\"$2]}" \
    "$RPC" 2>/dev/null
}
up()   { rpc aria2.getVersion | grep -q '"version"'; }
apid() { pgrep -f 'aria2c' | head -1; }

case "${1:-status}" in
start)
  if up; then echo "已在运行"; exit 0; fi
  nohup aria2c --conf-path="$CONF" >>"$LOG" 2>&1 &
  # 先探测再睡：Aria2 通常 100ms 内就绪，原来固定先 sleep 1 会白等一整秒
  for i in $(seq 1 100); do up && { echo "✅ Aria2 已启动（RPC :$PORT）"; exit 0; }; sleep 0.15; done
  echo "✗ 启动失败，看 $LOG"; exit 1;;
stop)
  pkill -f aria2c 2>/dev/null
  # 等进程真正退出（通常 <150ms），而不是无脑 sleep 1
  for i in $(seq 1 50); do up || break; sleep 0.1; done
  up && { echo "✗ 仍在运行"; exit 1; } || echo "已停止";;
restart) bash "$0" stop >/dev/null; bash "$0" start;;
ensure)  up || bash "$0" start >/dev/null 2>&1;;
json)    # 供 dsh-ctl.sh status 拼接
  if up; then
    st="$(rpc aria2.getGlobalStat)"
    ver="$(rpc aria2.getVersion | sed -n 's/.*"version":"\([^"]*\)".*/\1/p')"
    tasks="$(printf '%s' "$st" | sed -n 's/.*"numActive":"\([^"]*\)".*/\1/p')"
    speed="$(printf '%s' "$st" | sed -n 's/.*"downloadSpeed":"\([^"]*\)".*/\1/p')"
    printf '"ariaState":"UP","ariaPort":"%s","ariaPid":"%s","ariaTasks":"%s","ariaSpeed":"%s","ariaVersion":"%s"' \
      "$PORT" "$(apid)" "${tasks:-0}" "${speed:-0}" "$ver"
  else
    printf '"ariaState":"DOWN","ariaPort":"%s","ariaPid":"","ariaTasks":"0","ariaSpeed":"0","ariaVersion":""' "$PORT"
  fi;;
status)
  echo "=== Aria2 状态 ==="
  if up; then
    echo "  运行中（RPC :$PORT, pid $(apid)）"
    echo "  版本   $(rpc aria2.getVersion | sed -n 's/.*"version":"\([^"]*\)".*/\1/p')"
    rpc aria2.getGlobalStat | python3 -c "
import sys,json
d=json.load(sys.stdin).get('result',{})
sp=int(d.get('downloadSpeed','0'))
print('  下载中 %s 个 ｜ 等待 %s 个 ｜ 已停 %s 个' % (d.get('numActive','0'), d.get('numWaiting','0'), d.get('numStopped','0')))
print('  速度   %.2f MB/s  ｜ 累计下载 %.1f MB' % (sp/1048576, int(d.get('downloadLength','0'))/1048576))
" 2>/dev/null || rpc aria2.getGlobalStat
    echo "  目录   $(sed -n 's/^dir=//p' "$CONF")"
  else
    echo "  未运行"
  fi;;
info)
  # 机器可读（App 复制用）+ 人可读
  echo "ARIA_RPC=http://localhost:$PORT/jsonrpc"
  echo "ARIA_SECRET=$SECRET"
  echo "RPC 链接: http://localhost:$PORT/jsonrpc"
  echo "RPC 密钥: $SECRET";;
add)
  shift
  [ -n "${1:-}" ] || { echo "用法: aria2-ctl.sh add <url>..."; exit 1; }
  params="$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1:]))' "$@")"
  r="$(rpc aria2.addUri ",$params")"
  if printf '%s' "$r" | grep -q '"result"'; then
    echo "✅ 已加入 Aria2：$*"
  else
    echo "✗ 添加失败：$(printf '%s' "$r" | head -c 200)"
    exit 1
  fi;;

add-ext)
  # $2 = base64(url)，$3 = base64(文件名，可空) → 用 aria2 的 out 选项保住原始文件名
  # 注意：addUri 带 options 时 params 是三项 ["token:..", [url], {opts}]，
  # 这里自己拼完整 JSON（rpc() 只支持一个附加参数）
  [ -n "${2:-}" ] || { echo "用法: aria2-ctl.sh add-ext <b64url> [b64name]"; exit 1; }
  u="$(printf '%s' "$2" | base64 -d 2>/dev/null)"
  n=""
  [ -n "${3:-}" ] && n="$(printf '%s' "$3" | base64 -d 2>/dev/null)"
  [ -n "$u" ] || { echo "✗ url 解码失败"; exit 1; }
  body="$(python3 - "$u" "$n" "$SECRET" <<'PYX'
import json, sys
u, n, sec = sys.argv[1], sys.argv[2], sys.argv[3]
n = (n or "").replace("/", "_").strip()
params = ["token:" + sec, [u]] + ([{"out": n}] if n else [])
print(json.dumps({"jsonrpc": "2.0", "id": "a", "method": "aria2.addUri", "params": params}))
PYX
)"
  r="$(curl -s --max-time 15 -H 'Content-Type: application/json' -d "$body" "$RPC" 2>/dev/null)"
  if printf '%s' "$r" | grep -q '"result"'; then
    echo "✅ 已加入 Aria2：${n:-（按链接推断文件名）}"
  else
    echo "✗ 添加失败：$(printf '%s' "$r" | head -c 200)"; exit 1
  fi;;

add-b64)
  # URL 可能是分享来的（含 & ? # 等），用 base64 传避免 shell 转义问题
  [ -n "${2:-}" ] || { echo "用法: aria2-ctl.sh add-b64 <base64-url>"; exit 1; }
  u="$(printf '%s' "$2" | base64 -d 2>/dev/null)"
  [ -n "$u" ] || { echo "✗ base64 解码失败"; exit 1; }
  exec bash "$0" add "$u";;

log)  tail -c "${2:-3000}" "$LOG" 2>/dev/null || echo "（暂无日志）";;
secret) echo "$SECRET";;
*) echo "用法: aria2-ctl.sh {start|stop|restart|status|json|ensure|log|info|secret}"; exit 1;;
esac
