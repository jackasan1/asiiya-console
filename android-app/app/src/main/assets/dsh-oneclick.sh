#!/data/data/com.termux/files/usr/bin/bash
# ================================================================
#  dsh-oneclick.sh — DeepSeek Harness (dsh) 在 Android/Termux 一键部署
#
#  流程：环境预检 → 依赖/编译链 → dsh 安装 → Termux 兼容补丁
#        → 手机端 UI 适配 → 看门狗 → 开机自启 → 密钥/模型 → 系统优化 → 自检
#
#  用法：
#    bash dsh-oneclick.sh                     全自动部署
#    bash dsh-oneclick.sh --api-key sk-xxxx   顺带写入 API Key
#    bash dsh-oneclick.sh --verify            只体检，不改动
#    bash dsh-oneclick.sh --skip-npm          跳过 npm 安装（只重打补丁）
#
#  环境变量：DSH_PIN / DSH_MODEL / DSH_SKIP_UPGRADE
# ================================================================
set -uo pipefail

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOMEDIR="${HOME:-/data/data/com.termux/files/home}"
D="$PREFIX/lib/node_modules/@deepseek-ai/dsh"
PIN="${DSH_PIN:-0.1.5-rc.1}"
MODEL="${DSH_MODEL:-deepseek-flash}"
API_URL="https://github.com/termux/termux-api/releases/download/v0.53.0/termux-api-app_v0.53.0%2Bgithub.debug.apk"
API_KEY="${DSH_API_KEY:-}" ; MODE=deploy ; SKIP_NPM=0 ; NO_BOOT=0 ; NO_CSS=0 ; NO_WD=0

while [ $# -gt 0 ]; do
  case "$1" in
    --verify) MODE=verify ;;
    --skip-npm) SKIP_NPM=1 ;;
    --api-key) shift; API_KEY="${1:-}" ;;
    --model) shift; MODEL="${1:-}" ;;
    --pin) shift; PIN="${1:-}" ;;
    --no-boot) NO_BOOT=1 ;;
    --no-css) NO_CSS=1 ;;
    --no-watchdog) NO_WD=1 ;;
    -h|--help) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac; shift
done

C=$'\033[1;36m'; G=$'\033[1;32m'; Y=$'\033[1;33m'; R=$'\033[1;31m'; RST=$'\033[0m'
log(){ printf '%s==> %s%s\n' "$C" "$*" "$RST"; }
ok(){  printf '%s  ✓ %s%s\n' "$G" "$*" "$RST"; }
warn(){ printf '%s  ! %s%s\n' "$Y" "$*" "$RST"; }
err(){ printf '%s  ✗ %s%s\n' "$R" "$*" "$RST"; }
sec(){ printf '\n%s━━━ %s ━━━%s\n' "$C" "$*" "$RST"; }

# ============================ 0. 环境预检 ============================
sec "0/10 环境预检"
[ -n "$PREFIX" ] && [ -d "$PREFIX" ] || { err "不是 Termux 环境"; exit 1; }
ARCH="$(uname -m)"
case "$ARCH" in
  aarch64) TARGET=aarch64-linux-android30 ;;
  armv7l|armv8l) TARGET=armv7a-linux-androideabi30 ;;
  x86_64) TARGET=x86_64-linux-android30 ;;
  *) warn "未知架构 $ARCH，按 arm64 处理"; TARGET=aarch64-linux-android30 ;;
esac
ok "架构 $ARCH → 编译目标 $TARGET"
FREE_KB="$(df -k "$PREFIX" | tail -1 | awk '{print $4}')"
[ "${FREE_KB:-0}" -gt 1500000 ] && ok "剩余空间 $((FREE_KB/1024)) MB" || warn "空间偏小 $((FREE_KB/1024)) MB（建议 >1.5GB）"
ROOTOK=0
if command -v su >/dev/null 2>&1 && timeout 20 su -c 'id' 2>/dev/null | grep -q 'uid=0'; then ROOTOK=1; ok "检测到 root"; else warn "无 root（部分系统优化将跳过）"; fi


# ============================ --verify：只读体检 ============================
if [ "$MODE" = verify ]; then
  sec "体检报告（只读，不做任何修改）"
  [ -d "$D" ] && ok "dsh 已安装 ($(python3 -c "import json;print(json.load(open('$D/package.json'))['version'])" 2>/dev/null))" || err "dsh 未安装"
  [ -f "$D/node_modules/@deepseek-ai/node-addon-system/lib/flock-android.node" ] && ok "flock 原生模块存在" || err "flock 原生模块缺失"
  grep -q "platform === 'android'" "$D/node_modules/@deepseek-ai/node-addon-system/lib/flock.js" 2>/dev/null && ok "flock.js android 分支已打" || err "flock.js 未打补丁"
  grep -qE "^import \{[^}]*rename" "$D/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js" 2>/dev/null && ok "rename 导入就绪" || warn "rename 导入待确认"
  grep -q "defaultPreset" "$HOMEDIR/.dsh/profiles/web/cordis.patch.yml" 2>/dev/null && ok "权限预设已配置" || err "权限预设缺失"
  [ -f "$D/node_modules/@deepseek-ai/dsh-web-frontend/dist/assets/mobile-fix.css" ] && ok "手机端 CSS 已装" || warn "手机端 CSS 未装"
  [ -f "$HOMEDIR/.dsh/.credentials.yaml" ] && ok "凭据文件存在" || warn "凭据未配置"
  timeout 12 termux-battery-status 2>/dev/null | grep -q percentage && ok "Termux:API 应用已就绪" || warn "Termux:API 应用未就绪（部分 termux-* 命令不可用）"
  if [ -f "$HOMEDIR/dsh/dsh-watchdog.pid" ] && kill -0 "$(cat "$HOMEDIR/dsh/dsh-watchdog.pid" 2>/dev/null)" 2>/dev/null; then
    ok "看门狗运行中 (PID $(cat "$HOMEDIR/dsh/dsh-watchdog.pid"))"
  else warn "看门狗未运行"; fi
  [ -f "$HOMEDIR/.termux/boot/start-dsh.sh" ] && ok "开机自启脚本存在" || warn "开机自启脚本缺失"
  C2="$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 http://127.0.0.1:3080/ 2>/dev/null || echo 000)"
  if [ "$C2" = "401" ] || [ "$C2" = "200" ]; then ok "dsh web 响应正常 (HTTP $C2)"; else warn "dsh web 未响应 ($C2)"; fi
  echo
  echo "  当前地址: $(cat "$HOMEDIR/dsh/dsh-web-url.txt" 2>/dev/null)"
  exit 0
fi

# ============================ 1. 依赖 ============================
sec "1/10 依赖与编译工具链"
if [ "$MODE" = deploy ]; then
  if [ "${DSH_SKIP_UPGRADE:-0}" = "0" ]; then
    log "pkg update / upgrade（耗时较久，可设 DSH_SKIP_UPGRADE=1 跳过）"
    pkg update -y >/dev/null 2>&1; pkg upgrade -y >/dev/null 2>&1 || warn "pkg upgrade 有警告，继续"
  else warn "已跳过 pkg upgrade"; fi
  log "安装 git curl cmake clang make python binutils pkg-config nodejs"
  pkg install -y git curl cmake clang make python binutils pkg-config libandroid-spawn termux-tools nodejs >/dev/null 2>&1 \
    || pkg install -y git curl cmake clang make python binutils nodejs >/dev/null 2>&1
fi
command -v node >/dev/null || { err "node 未就绪"; exit 1; }
NM="$(node -p 'process.versions.node.split(".").slice(0,2).join(".")')"
node -e 'const [a,b]=process.versions.node.split(".").map(Number); process.exit(a>22||(a===22&&b>=12)?0:1)' \
  && ok "Node v$NM 满足要求（≥22.12）" || { err "Node v$NM 过低，请 pkg upgrade -y && pkg install nodejs"; exit 1; }
for c in clang cmake python3; do command -v "$c" >/dev/null && ok "$c 就绪" || warn "$c 缺失（编译原生模块可能失败）"; done

# ============================ 2. npm 配置 ============================
sec "2/10 npm 配置"
if [ "$MODE" = deploy ]; then
  npm config set allow-scripts "@deepseek-ai/dsh-subprocess-local,koffi,node-pty,@google/genai,protobufjs,pnpm" --location=user 2>/dev/null \
    && ok "install-scripts 白名单已放行" || warn "当前 npm 不支持 allow-scripts（通常无碍）"
  if ! curl -fsS --max-time 8 -o /dev/null https://registry.npmjs.org/-/ping 2>/dev/null; then
    warn "官方源不通 → 切换 npmmirror"; npm config set registry https://registry.npmmirror.com --location=user
  else ok "npm 源连通"; fi
  for kv in fetch-retries=5 fetch-retry-mintimeout=20000 fetch-retry-maxtimeout=120000 fetch-timeout=300000; do
    npm config set "${kv%%=*}" "${kv#*=}" --location=user >/dev/null 2>&1 || true
  done
  ok "超时/重试参数已优化"
fi

# ============================ 3. node-gyp 头文件 ============================
sec "3/10 node-gyp 头文件 + common.gypi 补丁"
ensure_headers(){
  local inc; inc="$(ls -d "$HOMEDIR"/.cache/node-gyp/*/include/node 2>/dev/null | tail -1)"
  if [ -z "$inc" ]; then
    local gyp; gyp="$(npm root -g 2>/dev/null)/npm/node_modules/node-gyp/bin/node-gyp.js"
    [ -f "$gyp" ] && node "$gyp" install >/dev/null 2>&1 || true
    inc="$(ls -d "$HOMEDIR"/.cache/node-gyp/*/include/node 2>/dev/null | tail -1)"
  fi
  printf '%s' "$inc"
}
if [ "$MODE" = deploy ]; then
  INC="$(ensure_headers)"
  if [ -n "$INC" ]; then
    ok "头文件: $INC"
    for f in "$HOMEDIR"/.cache/node-gyp/*/include/node/common.gypi; do
      [ -f "$f" ] || continue
      grep -q "android_ndk_path" "$f" && { ok "common.gypi 已含 android_ndk_path"; continue; }
      python3 - "$f" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
i=s.index("'variables': {")+len("'variables': {")
open(p,"w").write(s[:i]+"\n  'android_ndk_path%': '',"+s[i:])
PY
      ok "已修补 $f"
    done
  else warn "取不到头文件（node-pty 可能编译失败）"; fi
fi

# ============================ 4. 安装 dsh ============================
sec "4/10 安装 @deepseek-ai/dsh@$PIN"
if [ "$MODE" = deploy ] && [ "$SKIP_NPM" = "0" ]; then
  export CFLAGS="-target $TARGET" CXXFLAGS="-target $TARGET" CMAKE_BUILD_PARALLEL_LEVEL=2
  log "npm install -g @deepseek-ai/dsh@$PIN —— 最耗时（5~15 分钟，无输出属正常）"
  npm install -g --foreground-scripts --no-audit --no-fund "@deepseek-ai/dsh@$PIN" >/dev/null 2>&1 \
    || { warn "首次失败，切镜像重试"; npm config set registry https://registry.npmmirror.com --location=user; \
         npm install -g --foreground-scripts --no-audit --no-fund "@deepseek-ai/dsh@$PIN"; }
  unset CFLAGS CXXFLAGS CMAKE_BUILD_PARALLEL_LEVEL
else warn "跳过 npm 安装"; fi
command -v dsh >/dev/null && ok "dsh $(dsh --version 2>/dev/null | head -1)" || { err "dsh 命令未就位"; exit 1; }
[ -d "$D" ] || { err "未找到 $D"; exit 1; }

# ============================ 5. Termux/Android 后端补丁 ============================
sec "5/10 Termux / Android 后端补丁"
N="$D/node_modules/@deepseek-ai/node-addon-system"

# 5a. 编译 android 版 flock
if [ -f "$N/src/flock.c" ]; then
  INC2="$(ensure_headers)"
  if [ -n "$INC2" ] && command -v clang >/dev/null 2>&1; then
    clang -shared -fPIC -O2 -o "$N/lib/flock-android.node" "$N/src/flock.c" -I"$INC2" 2>/dev/null \
      && ok "flock-android.node 编译完成" || warn "flock 编译失败，将退化为无锁模式"
  else warn "缺 clang/头文件，跳过 flock 编译"; fi
else warn "未找到 flock.c（包结构可能已变）"; fi

# 5b. flock.js 加 android 分支
if [ -f "$N/lib/flock.js" ]; then
  cp -n "$N/lib/flock.js" "$N/lib/flock.js.bak" 2>/dev/null || true
  python3 - "$N/lib/flock.js" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
if "platform === 'android'" in s: print("  ✓ flock.js 已含 android 分支"); raise SystemExit
a="    const { platform, arch } = process;\n"
n="""    const { platform, arch } = process;
    if (platform === 'android') {
        try {
            const requireAndroid = createRequire(import.meta.url);
            const here = new URL('.', import.meta.url).pathname;
            binding = requireAndroid(`${here}flock-android.node`);
        } catch {
            binding = { tryLock: (_fd, cb) => queueMicrotask(() => cb(0)) };
        }
        return binding;
    }
"""
if a not in s: print("  ! flock.js 锚点丢失，跳过"); raise SystemExit
open(p,"w").write(s.replace(a,n,1)); print("  ✓ flock.js 已打补丁")
PY
fi

# 5c. rename 导入补齐（install.sh 的 link->rename 补丁配套）
python3 - <<'PY'
import re, os
D = os.environ["PREFIX"] + "/lib/node_modules/@deepseek-ai/dsh"
for p in [D+"/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
          D+"/node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js"]:
    if not os.path.exists(p): continue
    s = open(p).read(); tag = p.split("node_modules/")[-1]
    if not re.search(r'\brename\(', s): print("  · 未用 rename:", tag); continue
    def fix(m):
        n=[x.strip() for x in m.group(1).split(",") if x.strip()]
        if "rename" not in n: n.append("rename")
        return "import { " + ", ".join(sorted(set(n))) + ' } from "node:fs/promises";'
    s2 = re.sub(r'import \{([^}]*)\} from "node:fs/promises";', fix, s)
    if s2 != s: open(p,"w").write(s2); print("  ✓ 已补 rename 导入:", tag)
    else: print("  ✓ rename 导入已就绪:", tag)
PY

# 5d. web profile 权限预设
PROFILE="$HOMEDIR/.dsh/profiles/web/cordis.patch.yml"
mkdir -p "$(dirname "$PROFILE")"
if [ -f "$PROFILE" ] && grep -q defaultPreset "$PROFILE"; then ok "权限预设已配置"
else
  [ -f "$PROFILE" ] && cp -n "$PROFILE" "$PROFILE.bak" 2>/dev/null || true
  printf '\n- id: permission\n  config:\n    defaultPreset: danger-full-access\n' >> "$PROFILE"
  ok "已追加 defaultPreset"
fi

# ============================ 6. 手机端 UI 适配 ============================
sec "6/10 手机端 UI 适配"
F="$D/node_modules/@deepseek-ai/dsh-web-frontend/dist"
if [ "$NO_CSS" = "0" ] && [ -d "$F/assets" ]; then
  python3 - "$F" <<'PY'
import re, os, glob, sys
F = sys.argv[1]
base = os.path.dirname(os.path.dirname(F))          # …/@deepseek-ai
packs = glob.glob(base + "/dsh-client-ui-*/lib/client.js")
boxes = []
for f in packs:
    try: s = open(f, encoding="utf-8", errors="replace").read()
    except Exception: continue
    boxes.append((f, set(re.findall(r'\.([A-Za-z][A-Za-z0-9]{3,})_([A-Za-z][A-Za-z0-9]*)\{', s))))

def pick(*needles):
    for f, pr in boxes:
        cands = None
        for pre, name in pr:
            if name == needles[0]:
                if all((pre, n) in pr for n in needles):
                    cands = pre; break
        if cands: return cands
    return None

P_panel = pick('panel', 'nav', 'options')          # 设置面板模块
P_row   = pick('rowText', 'selector')              # 权限预设行
P_row2  = pick('rowText', 'stepper')               # 主题行
P_cube  = pick('cubeRow', 'themeCube')             # 外观卡片
print("  探测到类名前缀:", P_panel, P_row, P_row2, P_cube)


css = []
css.append("/* mobile-fix.css — 自动生成，勿手改（类名前缀按本机构建探测）*/")
css.append("@media (display-mode: fullscreen), (display-mode: standalone), (display-mode: minimal-ui) {")
css.append("  body { padding: env(safe-area-inset-top) env(safe-area-inset-right) env(safe-area-inset-bottom) env(safe-area-inset-left) !important; box-sizing: border-box !important; }")
css.append("}")

def rule(sel, body):
    css.append(f"{sel} {{ {body} }}")

if P_panel:
    css.append("@media (max-width: 720px) {")
    rule(f".{P_panel}_overlay", "padding-top: env(safe-area-inset-top) !important; padding-bottom: env(safe-area-inset-bottom) !important; box-sizing: border-box !important;")
    rule(f".{P_panel}_panel", "position: relative !important; width: 100% !important; max-width: 100% !important; height: 100% !important; max-height: 100% !important; border-radius: 0 !important; flex-direction: column !important;")
    rule(f".{P_panel}_header", "position: absolute !important; top: 6px !important; right: 8px !important; height: auto !important; padding: 0 !important; z-index: 3 !important;")
    rule(f".{P_panel}_nav", "width: 100% !important; flex: none !important; gap: 8px !important; padding: 14px 12px 6px !important;")
    rule(f".{P_panel}_navList", "flex-direction: row !important; flex-wrap: nowrap !important; overflow-x: auto !important; gap: 6px !important;")
    rule(f".{P_panel}_navCell", "flex: none !important; height: 34px !important; padding: 6px 12px !important;")
    rule(f".{P_panel}_content", "width: 100% !important;")
    rule(f".{P_panel}_options", "padding: 0 14px 18px !important;")
    css.append("}")
if P_row:
    css.append("@media (max-width: 720px) {")
    rule(f".{P_row}_row", "padding: 12px 0 !important; flex-wrap: wrap !important; row-gap: 8px !important;")
    rule(f".{P_row}_rowText", "padding-right: 12px !important;")
    rule(f".{P_row}_selector", "flex: none !important;")
    css.append("}")
if P_row2:
    css.append("@media (max-width: 720px) {")
    rule(f".{P_row2}_row", "padding: 12px 0 !important;")
    css.append("}")
if P_cube:
    css.append("@media (max-width: 720px) {")
    rule(f".{P_cube}_group", "padding: 12px 0 !important;")
    rule(f".{P_cube}_cubeRow", "flex-wrap: nowrap !important; gap: 6px !important;")
    rule(f".{P_cube}_cubeRow > .{P_cube}_themeCube", "flex: 1 1 0 !important; min-width: 0 !important; min-height: 0 !important; max-height: 92px !important; padding-top: 10px !important; padding-bottom: 10px !important;")
    css.append("}")

out = os.path.join(F, "assets", "mobile-fix.css")
open(out, "w").write("\n".join(css) + "\n")
print("  写入", out)

# 注入 <link>（带缓存击穿）
import hashlib
V = hashlib.md5(open(out,'rb').read()).hexdigest()[:10]
idx = os.path.join(F, "index.html")
s = open(idx).read()
tag = f'<link rel="stylesheet" crossorigin href="./assets/mobile-fix.css?v={V}" />'
s2 = re.sub(r'<link[^>]*mobile-fix\.css[^>]*/?>', tag, s)
if s2 == s:
    if "mobile-fix.css" not in s:
        s2 = s.replace("</head>", "    " + tag + "\n  </head>", 1)
    else:
        s2 = s
if s2 != s:
    if not os.path.exists(idx + ".bak"): open(idx + ".bak", "w").write(s)
    open(idx, "w").write(s2)
print("  已注入 <link> v=" + V)
PY
  ok "手机端适配已应用"
else warn "跳过 UI 适配"; fi

# ============================ 7. 看门狗 ============================
sec "7/10 看门狗（进程守护）"
if [ "$NO_WD" = "0" ]; then
cat > "$HOMEDIR/dsh/dsh-watchdog.sh" <<'WD'
#!/data/data/com.termux/files/usr/bin/bash
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"; export PATH="$PREFIX/bin:$PATH"
LOG="$HOME/dsh/dsh-web.log"; WDLOG="$HOME/dsh/dsh-watchdog.log"
URLFILE="$HOME/dsh/dsh-web-url.txt"; PIDFILE="$HOME/dsh/dsh-watchdog.pid"
say(){ echo "[$(date '+%F %T')] $*" >> "$WDLOG"; }
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE" 2>/dev/null)" 2>/dev/null; then echo "看门狗已在运行 (PID $(cat "$PIDFILE"))"; exit 0; fi
echo $$ > "$PIDFILE"; trap 'rm -f "$PIDFILE"' EXIT
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock 2>/dev/null && say "wakelock 已请求"
say "看门狗启动 (PID $$)"
while true; do
  if ! pgrep -f "expose-internals" >/dev/null 2>&1; then
    say "dsh web 不在运行 → 拉起"; cd "$HOME" || exit 1
    setsid dsh web > "$LOG" 2>&1 < /dev/null &
    sleep 25; U="$(head -1 "$LOG" 2>/dev/null)"; printf '%s\n' "$U" > "$URLFILE"; say "已拉起: $U"
  fi
  sleep 60
done
WD
  chmod +x "$HOMEDIR/dsh/dsh-watchdog.sh"
  if [ -f "$HOMEDIR/dsh/dsh-watchdog.pid" ] && kill -0 "$(cat "$HOMEDIR/dsh/dsh-watchdog.pid" 2>/dev/null)" 2>/dev/null; then
    ok "看门狗已在运行 (PID $(cat "$HOMEDIR/dsh/dsh-watchdog.pid"))"
  else
    setsid bash "$HOMEDIR/dsh/dsh-watchdog.sh" >/dev/null 2>&1 < /dev/null & sleep 6
    ok "看门狗已启动 (PID $(cat "$HOMEDIR/dsh/dsh-watchdog.pid" 2>/dev/null))"
  fi
else warn "跳过看门狗"; fi

# ============================ 8. 开机自启 ============================
sec "8/10 开机自启（Termux:Boot）"
if [ "$NO_BOOT" = "0" ]; then
  mkdir -p "$HOMEDIR/.termux/boot"
cat > "$HOMEDIR/.termux/boot/start-dsh.sh" <<'BOOT'
#!/data/data/com.termux/files/usr/bin/bash
export PATH="/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets:$PATH"
LOG="$HOME/dsh/dsh-boot.log"
echo "[$(date '+%F %T')] boot 脚本被触发" >> "$LOG"
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock 2>/dev/null
setsid bash "$HOME/dsh/dsh-watchdog.sh" >> "$HOME/dsh/dsh-watchdog.log" 2>&1 < /dev/null &
echo "[$(date '+%F %T')] 已拉起看门狗" >> "$LOG"
BOOT
  chmod +x "$HOMEDIR/.termux/boot/start-dsh.sh"
  ok "开机脚本: ~/.termux/boot/start-dsh.sh"
  # BootActivity 是 exported 的，非 root 也能拉起（拉起一次即完成开机广播注册）
  ST=""
  if [ "$ROOTOK" = "1" ]; then
    ST="$(timeout 20 su -c 'dumpsys package com.termux.boot' 2>/dev/null | grep -o 'stopped=[a-z]*' | head -1)"
  fi
  if [ "$ST" = "stopped=true" ] || [ -z "$ST" ]; then
    log "拉起 Termux:Boot 界面一次"
    am start -n com.termux.boot/com.termux.boot.BootActivity >/dev/null 2>&1 \
      || /system/bin/am start -n com.termux.boot/com.termux.boot.BootActivity >/dev/null 2>&1 \
      || warn "自动拉起失败，请手动打开一次 Termux:Boot"
    sleep 4
    if [ "$ROOTOK" = "1" ]; then
      ST2="$(timeout 20 su -c 'dumpsys package com.termux.boot' 2>/dev/null | grep -o 'stopped=[a-z]*' | head -1)"
      [ "$ST2" = "stopped=false" ] && ok "Termux:Boot 已激活" || warn "仍为 $ST2，请手动打开一次 Termux:Boot"
    else
      ok "已尝试拉起（屏幕出现 Termux:Boot 界面即为成功）"
    fi
  else
    ok "Termux:Boot 已激活（$ST）"
  fi
else warn "跳过开机自启"; fi

# ============================ 9. 密钥 / 模型 ============================
sec "9/10 凭据与模型"
CF="$HOMEDIR/.dsh/.credentials.yaml"
mkdir -p "$HOMEDIR/.dsh"
if [ -n "$API_KEY" ]; then
  [ -f "$CF" ] && cp -n "$CF" "$CF.bak" 2>/dev/null || true
  [ -f "$CF" ] || printf 'version: 1\nrefs: {}\n' > "$CF"
  python3 - "$CF" "$API_KEY" <<'PY'
import re,sys
p,k=sys.argv[1],sys.argv[2]; s=open(p).read()
if "DEEPSEEK_API_KEY:" in s:
    s=re.sub(r'(?m)^(\s*DEEPSEEK_API_KEY:\s*).*$', lambda m:m.group(1)+k, s)
elif re.search(r'(?m)^refs:\s*$', s):
    s=re.sub(r'(?m)^refs:\s*$','refs:\n  DEEPSEEK_API_KEY: '+k,s,count=1)
else:
    s=s.rstrip("\n")+"\nrefs:\n  DEEPSEEK_API_KEY: "+k+"\n"
open(p,"w").write(s)
PY
  chmod 600 "$CF"
  ok "API Key 已写入（权限 600）"
  if curl -s -o /dev/null -w '%{http_code}' --max-time 20 https://api.deepseek.com/models -H "Authorization: Bearer $API_KEY" | grep -q 200; then
    ok "Key 校验通过"
  else warn "Key 校验未返回 200，请确认有效性/网络"; fi
else warn "未提供 --api-key，稍后可在 Web UI「设置 → 模型」里填"; fi

SF="$HOMEDIR/.dsh/settings.yaml"
python3 - "$SF" "$MODEL" <<'PY' 2>/dev/null || true
import os,sys,re
p,m=sys.argv[1],sys.argv[2]
if not os.path.exists(p): raise SystemExit
s=open(p).read(); L=s.split("\n"); done=False
for i,l in enumerate(L):
    if l.startswith("agent-default-model:"):
        for j in range(i+1,len(L)):
            if L[j] and not L[j][0].isspace(): break
            if L[j].strip().startswith("model:"):
                L[j]=L[j][:len(L[j])-len(L[j].lstrip())]+"model: "+m; done=True; break
        break
if done: open(p,"w").write("\n".join(L))
PY
[ -f "$SF" ] && ok "默认模型: $MODEL" || warn "settings.yaml 不存在（首次启动后自动生成）"

# ============================ 10. 系统优化 + 自检 ============================
sec "10/10 系统优化与自检"
# —— Doze 白名单 ——
if [ "$MODE" = deploy ]; then
  if [ "$ROOTOK" = "1" ]; then
    timeout 20 su -c 'dumpsys deviceidle whitelist +com.termux' >/dev/null 2>&1 \
      && ok "Termux 已加入 Doze 白名单" || warn "Doze 白名单设置失败"
  else
    warn "无 root：请手动设置 系统设置 → 应用 → Termux → 电池 → 无限制"
  fi
fi

# —— Termux:API（非 root 也能装：下载 APK + 拉起安装界面）——
API_OK=0
timeout 12 termux-battery-status 2>/dev/null | grep -q percentage && API_OK=1
if [ "$API_OK" = "1" ]; then
  ok "Termux:API 应用已就绪"
elif [ "$MODE" = deploy ]; then
  warn "Termux:API 未就绪，尝试获取官方 APK"
  DL="$HOMEDIR/storage/shared/Download"
  if mkdir -p "$DL" 2>/dev/null && curl -fsSL --max-time 180 -o "$DL/termux-api.apk" "$API_URL" 2>/dev/null; then
    SUM="$(sha256sum "$DL/termux-api.apk" 2>/dev/null | awk '{print $1}')"
    OFF="$(curl -fsSL --max-time 30 "${API_URL%/*}/checksums-sha256.txt" 2>/dev/null | awk '{print $1}' | head -1)"
    if [ -n "$SUM" ] && [ "$SUM" = "$OFF" ]; then ok "APK 校验通过（sha256 与官方一致）"; else warn "校验和不一致（本地 $SUM）"; fi
    ok "APK 已保存: /sdcard/Download/termux-api.apk"
    if [ "$ROOTOK" = "1" ]; then
      timeout 150 su -c "pm install -r '$DL/termux-api.apk'" >/dev/null 2>&1 && ok "已自动安装" || warn "自动安装失败，请手动点 APK"
    else
      termux-open "$DL/termux-api.apk" >/dev/null 2>&1 || true
      warn "无 root：请用文件管理器点开该 APK 完成安装"
    fi
  else warn "APK 下载失败（网络？）"; fi
else
  warn "未检测到 Termux:API（部分 termux-* 命令不可用）"
fi

[ -f "$HOMEDIR/dsh/dsh-web-url.txt" ] || head -1 "$HOMEDIR/dsh/dsh-web.log" > "$HOMEDIR/dsh/dsh-web-url.txt" 2>/dev/null || true
sleep 5
CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 http://127.0.0.1:3080/ 2>/dev/null || echo 000)"
if [ "$CODE" = "401" ] || [ "$CODE" = "200" ]; then
  ok "dsh web 正常（HTTP $CODE）"
else
  warn "dsh web 未响应（HTTP $CODE）"
fi

if [ "$ROOTOK" = "0" ]; then
  printf '\n%s── 无 root，请手动完成这 4 件事 ──%s\n' "$Y" "$RST"
  echo "  ① Termux:API   → 安装 /sdcard/Download/termux-api.apk（脚本已为你下载）"
  echo "  ② Termux:Boot  → https://github.com/termux/termux-boot/releases 装好并【打开一次】"
  echo "  ③ 关电池优化   → 系统设置 → 应用 → Termux → 电池 → 无限制"
  echo "  ④ 自启动权限   → MIUI/ColorOS 等需额外给 Termux:Boot 开「自启动」"
  echo
fi
printf '\n%s════════════════ 部署完成 ════════════════%s\n' "$G" "$RST"
echo "  启动方式 : dsh web"
echo "  当前地址 : $(cat "$HOMEDIR/dsh/dsh-web-url.txt" 2>/dev/null)"
echo "  看门狗   : ~/dsh-watchdog.sh   (日志 ~/dsh-watchdog.log)"
echo "  开机自启 : ~/.termux/boot/start-dsh.sh"
echo "  取地址   : cat ~/dsh-web-url.txt"
echo
echo "  ⚠️ 若 Termux:API 未装，去 https://github.com/termux/termux-api/releases 装 APK"
echo "  ⚠️ Termux:Boot 装完必须【手动打开一次】，否则开机广播收不到"
