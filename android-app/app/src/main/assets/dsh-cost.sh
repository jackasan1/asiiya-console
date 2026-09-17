#!/data/data/com.termux/files/usr/bin/bash
# dsh-cost.sh — DSH 调用费用查询
#   余额      DeepSeek 官方余额（实时，用 API Key 查 /user/balance）
#   用量/费用  扫描 ~/.dsh/sessions 的会话记录，按官方峰谷单价估算
#   对账     余额快照差值 = 实际扣费，可与本地估算互校
# 用法: bash ~/dsh/dsh-cost.sh [report|balance|usage [today|7d|30d|all|YYYY-MM-DD]|daily [N]|snapshot|json|help]
exec python3 - "$@" <<'PY'
# -*- coding: utf-8 -*-
"""dsh-cost — DeepSeek 调用费用查询（余额 / 本地用量估算 / 余额对账）"""
import os, re, sys, json, time, glob, subprocess, datetime

BASE   = os.path.expanduser("~/dsh")
SESS   = os.path.expanduser("~/.dsh/sessions")
BALLOG = os.path.join(BASE, "cost-balance.tsv")
KEYF   = os.path.join(BASE, ".apikey")
CRED   = os.path.expanduser("~/.dsh/.credentials.yaml")

# 单价：元 / 百万 tokens，(空闲时段, 高峰时段)
# 高峰 = 周一至周五 09:00-12:00、14:00-18:00（北京时间），其余为空闲（半价）
PRICE = {
    "flash": {"hit": (0.02, 0.04), "miss": (1.00, 2.00), "out": (4.00, 8.00)},
    "pro":   {"hit": (0.15, 0.30), "miss": (4.50, 9.00), "out": (13.50, 27.00)},
}

def tier(model):
    return "pro" if "pro" in (model or "").lower() else "flash"

def is_peak(dt):
    return dt.weekday() < 5 and (9 <= dt.hour < 12 or 14 <= dt.hour < 18)

def dt_of(ms):
    return datetime.datetime.fromtimestamp(ms / 1000.0)

def tk(n):
    n = float(n)
    if n >= 1e6: return "%.2fM" % (n / 1e6)
    if n >= 1e3: return "%.1fK" % (n / 1e3)
    return "%d" % n

def money(v):
    return ("¥%.4f" % v) if abs(v) < 1 else ("¥%.2f" % v)

# ---------------- 官方余额 ----------------
def api_key():
    k = (os.environ.get("DEEPSEEK_API_KEY") or "").strip()
    if k: return k
    try:
        k = open(KEYF).read().strip()
        if k: return k
    except Exception:
        pass
    try:
        m = re.search(r'DEEPSEEK_API_KEY:\s*([^\s#]+)', open(CRED).read())
        if m: return m.group(1).strip().strip('"\'')
    except Exception:
        pass
    return ""

def balance():
    k = api_key()
    if not k:
        return {"error": "未找到 API Key（可先执行 dsh-ctl.sh setkey）"}
    try:
        r = subprocess.run(["curl", "-s", "--max-time", "20",
                            "-H", "Authorization: Bearer " + k,
                            "https://api.deepseek.com/user/balance"],
                           capture_output=True, text=True, timeout=30)
        d = json.loads(r.stdout)
        b = (d.get("balance_infos") or [{}])[0]
        return {"available": bool(d.get("is_available")), "currency": b.get("currency", "CNY"),
                "total": float(b.get("total_balance") or 0),
                "granted": float(b.get("granted_balance") or 0),
                "topped_up": float(b.get("topped_up_balance") or 0),
                "at": datetime.datetime.now().strftime("%Y-%m-%d %H:%M")}
    except Exception as e:
        return {"error": "余额查询失败: %s" % e}

def bal_line(b):
    if "error" in b: return "   ✗ " + b["error"]
    return "   %s %s   赠送 %s / 充值 %s   （%s）" % (
        b["currency"], money(b["total"]), money(b["granted"]), money(b["topped_up"]), b["at"])

# ---------------- 本地用量 ----------------
def scan():
    recs = []
    for f in sorted(glob.glob(os.path.join(SESS, "**", "session.v3.jsonl.zstd"), recursive=True)):
        sid = os.path.basename(os.path.dirname(f))
        try:
            p = subprocess.Popen(["zstd", "-dc", f], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        except Exception:
            continue
        for raw in p.stdout:
            if b'"usage"' not in raw: continue
            try: r = json.loads(raw)
            except Exception: continue
            if r.get("type") != "assistant/message": continue
            d = r.get("data") or {}
            u = d.get("usage") or {}
            src = ((d.get("message") or {}).get("source") or {})
            recs.append({"ts": r.get("time") or 0, "model": src.get("model") or "unknown",
                         "provider": src.get("provider") or "-",
                         "miss": int(u.get("inputTokens") or 0),
                         "hit":  int(u.get("cacheReadTokens") or 0),
                         "out":  int(u.get("outputTokens") or 0),
                         "reason": int(u.get("reasoningTokens") or 0),
                         "sess": sid})
        try: p.wait()
        except Exception: pass
    recs.sort(key=lambda r: r["ts"])
    return recs

def cost_of(r):
    p = PRICE[tier(r["model"])]
    i = 1 if is_peak(dt_of(r["ts"])) else 0
    return r["miss"] / 1e6 * p["miss"][i] + r["hit"] / 1e6 * p["hit"][i] + r["out"] / 1e6 * p["out"][i]

def agg(recs):
    t = {"miss": 0, "hit": 0, "out": 0, "reason": 0, "cost": 0.0, "n": 0, "peak": 0,
         "from": 0, "to": 0}
    for r in recs:
        t["miss"] += r["miss"]; t["hit"] += r["hit"]; t["out"] += r["out"]
        t["reason"] += r["reason"]; t["cost"] += cost_of(r); t["n"] += 1
        if is_peak(dt_of(r["ts"])): t["peak"] += 1
        if not t["from"] or r["ts"] < t["from"]: t["from"] = r["ts"]
        if r["ts"] > t["to"]: t["to"] = r["ts"]
    return t

def cut(recs, since=None, until=None):
    return [r for r in recs
            if (since is None or r["ts"] >= since) and (until is None or r["ts"] < until)]

def midnight(d=None):
    d = d or datetime.datetime.now()
    return d.replace(hour=0, minute=0, second=0, microsecond=0)

def ms(dt):
    return dt.timestamp() * 1000

def win(recs, name):
    now = datetime.datetime.now()
    if name in (None, "", "all"):  return recs, "累计"
    if name == "today":            return cut(recs, ms(midnight(now))), "今日"
    if name == "yesterday":
        y = midnight(now) - datetime.timedelta(days=1)
        return cut(recs, ms(y), ms(y + datetime.timedelta(days=1))), "昨日"
    m = re.match(r'^(\d+)\s*d$', name)
    if m:
        n = int(m.group(1))
        return cut(recs, ms(midnight(now) - datetime.timedelta(days=n - 1))), "近%d天" % n
    m = re.match(r'^(\d+)\s*-\s*(\d+)$', name)
    if m:  # 自定义区间 09-01:09-15
        y = now.year
        a = datetime.datetime.strptime("%d-%s" % (y, m.group(1)), "%Y-%m-%d")
        z = datetime.datetime.strptime("%d-%s" % (y, m.group(2)), "%Y-%m-%d")
        return cut(recs, ms(a), ms(z + datetime.timedelta(days=1))), "%s~%s" % (m.group(1), m.group(2))
    if re.match(r'^\d{4}-\d{2}-\d{2}$', name):
        d = datetime.datetime.strptime(name, "%Y-%m-%d")
        return cut(recs, ms(d), ms(d + datetime.timedelta(days=1))), name
    return None, None

def row(label, t):
    if not t["n"]:
        return "   %-7s ——（无记录）" % label
    return "   %-7s %3d 轮 | 输入 miss %-8s hit %-8s | 输出 %-8s | ≈ %s" % (
        label, t["n"], tk(t["miss"]), tk(t["hit"]), tk(t["out"]), money(t["cost"]))

# ---------------- 快照对账 ----------------
def snap_read():
    rows = []
    if os.path.exists(BALLOG):
        for ln in open(BALLOG):
            p = ln.rstrip("\n").split("\t")
            if len(p) >= 2:
                try:
                    rows.append({"t": int(p[0]), "total": float(p[1]),
                                 "granted": float(p[2]) if len(p) > 2 else 0.0,
                                 "topped": float(p[3]) if len(p) > 3 else 0.0})
                except Exception:
                    pass
    return rows

def cmd_snapshot():
    b = balance()
    if "error" in b:
        print("✗ " + b["error"]); return 1
    with open(BALLOG, "a") as f:
        f.write("%d\t%.4f\t%.4f\t%.4f\n" % (time.time() * 1000, b["total"], b["granted"], b["topped_up"]))
    print("✅ 已记录余额快照：%s（%s）" % (money(b["total"]), BALLOG))
    return 0

# ---------------- 子命令 ----------------
def cmd_report(recs):
    now = datetime.datetime.now()
    b = balance()
    print("💰 DeepSeek 余额"); print(bal_line(b))
    nsess = len(set(r["sess"] for r in recs))
    print("📊 本地用量估算（%d 个会话 · %d 轮 · 官方峰谷单价）" % (nsess, len(recs)))
    print(row("今日",   agg(cut(recs, ms(midnight(now))))))
    print(row("昨日",   agg(cut(recs, ms(midnight(now) - datetime.timedelta(days=1)), ms(midnight(now))))))
    print(row("近7天",  agg(cut(recs, ms(midnight(now) - datetime.timedelta(days=6))))))
    print(row("本月",   agg(cut(recs, ms(midnight(now.replace(day=1)))))))
    print(row("累计",   agg(recs)))
    mr = cut(recs, ms(midnight(now.replace(day=1))))
    if mr:
        by = {}
        for r in mr: by.setdefault(r["model"], []).append(r)
        print("🏷 本月分模型")
        for k in sorted(by, key=lambda k: -agg(by[k])["cost"]):
            t = agg(by[k])
            print("   %-32s %3d 轮  ≈ %s  （高峰 %d 轮）" % (k, t["n"], money(t["cost"]), t["peak"]))
    rows = snap_read()
    if rows and "error" not in b:
        last = rows[-1]
        days = (time.time() * 1000 - last["t"]) / 86400000.0
        delta = last["total"] - b["total"]
        est = agg(cut(recs, last["t"]))["cost"]
        print("📈 余额对账（上次快照 → 现在）")
        print("   %s %s → %s ｜ %.1f 天 ｜ 实际扣费 %s" % (
            datetime.datetime.fromtimestamp(last["t"] / 1000).strftime("%m-%d %H:%M"),
            money(last["total"]), money(b["total"]), days, money(delta)))
        print("   同期本地估算 %s（差 %s）%s" % (
            money(est), money(delta - est),
            "   ⚠ 期间可能充值过，差值仅供参考" if delta < 0 else ""))
    print("   提示: 单价见 api-docs.deepseek.com/zh-cn/quick_start/pricing")

def cmd_usage(recs, spec):
    sel, label = win(recs, spec)
    if sel is None:
        print("✗ 未知范围: %s（today / yesterday / 7d / 30d / all / YYYY-MM-DD / MM-DD:MM-DD）" % spec)
        return 1
    t = agg(sel)
    print("📊 %s用量" % label)
    print(row(label, t))
    if t["n"]:
        print("   输入 miss %s ｜ hit %s（缓存命中率 %.0f%%）｜ 输出 %s（其中思考 %s）" % (
            tk(t["miss"]), tk(t["hit"]),
            100.0 * t["hit"] / max(1, t["hit"] + t["miss"]),
            tk(t["out"]), tk(t["reason"])))
        print("   区间 %s → %s ｜ 高峰 %d 轮 / %d 轮" % (
            dt_of(t["from"]).strftime("%m-%d %H:%M"), dt_of(t["to"]).strftime("%m-%d %H:%M"),
            t["peak"], t["n"]))
        by = {}
        for r in sel: by.setdefault(r["model"], []).append(r)
        for k in sorted(by, key=lambda k: -agg(by[k])["cost"]):
            tt = agg(by[k])
            print("   · %-32s %3d 轮  ≈ %s" % (k, tt["n"], money(tt["cost"])))
    return 0

def cmd_daily(recs, n=14):
    print("📅 每日用量（近 %d 天）" % n)
    print("   日期         轮次   输入miss   输入hit    输出      估算费用")
    total = 0.0
    for i in range(n - 1, -1, -1):
        d0 = midnight() - datetime.timedelta(days=i)
        t = agg(cut(recs, ms(d0), ms(d0 + datetime.timedelta(days=1))))
        total += t["cost"]
        print("   %s  %4d   %8s   %8s  %8s   %s" % (
            d0.strftime("%m-%d %a"), t["n"], tk(t["miss"]), tk(t["hit"]), tk(t["out"]), money(t["cost"])))
    print("   %-34s 合计 ≈ %s" % ("", money(total)))

def cmd_json(recs):
    now = datetime.datetime.now()
    b = balance()
    def pack(t): return {"turns": t["n"], "miss": t["miss"], "hit": t["hit"], "out": t["out"],
                         "reason": t["reason"], "cost": round(t["cost"], 4), "peakTurns": t["peak"]}
    out = {"balance": b,
           "today": pack(agg(cut(recs, ms(midnight(now))))),
           "week":  pack(agg(cut(recs, ms(midnight(now) - datetime.timedelta(days=6))))),
           "month": pack(agg(cut(recs, ms(midnight(now.replace(day=1)))))),
           "total": pack(agg(recs)),
           "sessions": len(set(r["sess"] for r in recs))}
    print(json.dumps(out, ensure_ascii=False))

def main():
    argv = sys.argv[1:]
    cmd = (argv[0] if argv else "report").lower()
    if cmd in ("help", "-h", "--help", "用法"):
        print(__doc__); return 0
    if cmd in ("snapshot", "快照", "log"): return cmd_snapshot()
    if cmd in ("balance", "余额"):
        b = balance()
        if "error" in b: print("✗ " + b["error"]); return 1
        print("%s %s   赠送 %s / 充值 %s   （%s）" % (
            b["currency"], money(b["total"]), money(b["granted"]), money(b["topped_up"]), b["at"]))
        return 0
    recs = scan()
    if cmd in ("report", "概览", ""):            cmd_report(recs)
    elif cmd in ("usage", "用量"):               return cmd_usage(recs, argv[1] if len(argv) > 1 else "all")
    elif cmd in ("daily", "按天"):               cmd_daily(recs, int(argv[1]) if len(argv) > 1 and argv[1].isdigit() else 14)
    elif cmd == "json":                          cmd_json(recs)
    else:
        print("✗ 未知命令: %s" % cmd); print(__doc__); return 1
    return 0

sys.exit(main())
PY
