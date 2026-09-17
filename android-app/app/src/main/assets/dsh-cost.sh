#!/data/data/com.termux/files/usr/bin/bash
# dsh-cost.sh — DeepSeek 官方费用查询
#
# 数据源全部来自 platform.deepseek.com 官方接口（不是本地估算）：
#   /api/v0/usage/by_api_key/cost   消费金额（按小时/模型/API Key 分桶）
#   /api/v0/usage/by_api_key/amount 请求数 + tokens（cache hit/miss/response）
#   /api/v0/users/get_user_summary  余额 + 累计消费
# 认证用平台 token：~/dsh/.dsp_token（浏览器登录后由 agent 落盘，600 权限）
#
# 用法:
#   dsh-cost.sh                # 概览：余额/累计 + 今日/昨日/本月/近30天 + 分模型 + 分Key
#   dsh-cost.sh today|yesterday|month|d30
#   dsh-cost.sh hourly [天数]  # 逐小时（默认今天）
#   dsh-cost.sh keys|models    # 分 API Key / 分模型（今天）
#   dsh-cost.sh daily [30]     # 近 N 天（官方天桶 + 柱状）
#   dsh-cost.sh peak           # 当前峰/谷档位与倒计时
#   dsh-cost.sh json           # 供 App / 定时任务消费
#   dsh-cost.sh sample         # 余额快照（官方接口不可用时的兜底曲线）
#   dsh-cost.sh check          # 自检：token 是否有效
exec python3 - "$@" <<'PY'
# -*- coding: utf-8 -*-
"""dsh-cost — DeepSeek 官方用量/费用查询"""
import os, sys, json, time, subprocess, datetime

BASE   = os.path.expanduser("~/dsh")
TOKF   = os.path.join(BASE, ".dsp_token")
CFGF   = os.path.join(BASE, "cost-config.sh")
CACHE  = os.path.join(BASE, ".cost-cache.json")
BALLOG = os.path.join(BASE, "cost-balance.tsv")
UA = ("Mozilla/5.0 (Linux; Android 16; PLR110) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36")

# ---------------- 基础 ----------------
def tz_off():
    off = -time.timezone if not time.daylight else -time.altzone
    return int(off // 900 * 900)          # 接口要求 900 的整数倍

def token():
    try:
        return open(TOKF).read().strip()
    except Exception:
        return ""

def api(path, timeout=40):
    t = token()
    if not t:
        return {"_err": "NO_TOKEN"}
    try:
        out = subprocess.run(
            ["curl", "-s", "--max-time", str(timeout),
             "-H", "Authorization: Bearer " + t,
             "-H", "User-Agent: " + UA,
             "-H", "Origin: https://platform.deepseek.com",
             "-H", "Referer: https://platform.deepseek.com/usage",
             "-H", "Accept: application/json, text/plain, */*",
             "https://platform.deepseek.com/api/v0/" + path],
            capture_output=True, text=True, timeout=timeout + 20).stdout
    except Exception as e:
        return {"_err": "NET:%s" % e}
    try:
        d = json.loads(out)
    except Exception:
        return {"_err": "BAD_JSON:" + out[:60].replace("\n", " ")}
    if d.get("code") != 0:
        return {"_err": d.get("msg") or ("code %s" % d.get("code"))}
    bd = d.get("data") or {}
    if bd.get("biz_code") != 0:
        return {"_err": bd.get("biz_msg") or "BIZ_ERR"}
    return bd.get("biz_data") or {}

def cache_get(k, ttl):
    try:
        c = json.load(open(CACHE))
    except Exception:
        c = {}
    e = c.get(k)
    if e and time.time() - e.get("at", 0) < ttl:
        return e.get("v")
    return None

def cache_put(k, v):
    try:
        c = json.load(open(CACHE))
    except Exception:
        c = {}
    c[k] = {"at": time.time(), "v": v}
    for kk in list(c):
        if time.time() - c[kk].get("at", 0) > 7 * 86400:
            c.pop(kk, None)
    try:
        json.dump(c, open(CACHE, "w"))
    except Exception:
        pass

# ---------------- 区间 ----------------
def midnight(days_ago=0):
    d = (datetime.datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
         - datetime.timedelta(days=days_ago))
    return int(d.timestamp())

def rng(days_ago=0, span=1):
    s = midnight(days_ago)
    return s, s + 86400 * span

def range_of(name):
    """返回 (start, end, label)。接口要求整天对齐"""
    now = datetime.datetime.now()
    if name == "today":     s, e = rng(0, 1);  return s, e, "今日"
    if name == "yesterday": s, e = rng(1, 1);  return s, e, "昨日"
    if name == "month":
        first = now.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
        s = int(first.timestamp()); span = (now.date() - first.date()).days + 1
        return s, s + 86400 * span, "本月"
    if name in ("d30", "30d"): s, e = rng(29, 30); return s, e, "近30天"
    if name in ("d7", "7d"):   s, e = rng(6, 7);   return s, e, "近7天"
    return None

def ttl_for(e):
    return 60 if e > time.time() else 3600

# ---------------- 峰谷时段（北京时间工作日 09-12 / 14-18 为峰，周末全谷价）----------------
def is_peak_dt(d):
    if d.weekday() >= 5:            # 周六/周日：全天谷价
        return False
    return (9 <= d.hour < 12) or (14 <= d.hour < 18)

def peak_state(now=None):
    now = now or datetime.datetime.now()
    cur = is_peak_dt(now)
    t = now.replace(minute=0, second=0, microsecond=0) + datetime.timedelta(hours=1)
    for _ in range(24 * 9):
        if is_peak_dt(t) != cur: break
        t += datetime.timedelta(hours=1)
    return {"isPeak": cur, "switchAt": int(t.timestamp()),
            "minutesLeft": int((t - now).total_seconds() // 60),
            "weekend": now.weekday() >= 5}

def peak_split(hourly):
    """按峰/谷归类官方小时桶"""
    pk = off = 0.0
    for h, v in (hourly or {}).items():
        if is_peak_dt(datetime.datetime.fromtimestamp(int(h) * 3600)): pk += v
        else: off += v
    return pk, off

def cfg():
    c = {"DAILY_BUDGET": 0.0, "MONTH_BUDGET": 0.0, "PEAK_ALERT_MIN": 10}
    try:
        for line in open(CFGF):
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line: continue
            k, v = line.split("=", 1); k = k.strip()
            if k in c:
                try: c[k] = float(v.strip())
                except Exception: pass
    except Exception:
        pass
    c["PEAK_ALERT_MIN"] = int(c["PEAK_ALERT_MIN"])
    return c

def fmt_left(m):
    h, mm = divmod(max(0, int(m)), 60)
    return ("%dh%02dm" % (h, mm)) if h else ("%dm" % mm)

# ---------------- 官方数据 ----------------
def cost_of(name):
    r = range_of(name)
    if not r: return {"err": "BAD_RANGE"}
    s, e, label = r
    ck = "cost:%d:%d" % (s, e)
    c = cache_get(ck, ttl_for(e))
    if c is not None:
        c["label"] = label; return c
    bd = api("usage/by_api_key/cost?start=%d&end=%d&tz=%d" % (s, e, tz_off()))
    res = {"label": label, "start": s, "end": e, "total": 0.0,
           "byModel": {}, "byKey": {}, "hourly": {}, "err": None}
    if "_err" in bd:
        res["err"] = bd["_err"]; return res
    for blk in bd.get("data") or []:
        for row in blk.get("series") or []:
            m = row.get("model") or "?"
            k = (row.get("api_key") or {}).get("name") or "?"
            for b in row.get("buckets") or []:
                v = float(b.get("cost") or 0)
                if v <= 0: continue
                res["total"] += v
                res["byModel"][m] = res["byModel"].get(m, 0) + v
                res["byKey"][k] = res["byKey"].get(k, 0) + v
                h = int((b.get("time") or 0) // 3600)
                res["hourly"][str(h)] = res["hourly"].get(str(h), 0) + v
    cache_put(ck, res)
    return res

def amount_of(name):
    r = range_of(name)
    if not r: return {"err": "BAD_RANGE"}
    s, e, label = r
    ck = "amt:%d:%d" % (s, e)
    c = cache_get(ck, ttl_for(e))
    if c is not None:
        c["label"] = label; return c
    bd = api("usage/by_api_key/amount?start=%d&end=%d&tz=%d" % (s, e, tz_off()))
    res = {"label": label, "req": 0, "resp": 0, "hit": 0, "miss": 0, "byModel": {}, "err": None}
    if "_err" in bd:
        res["err"] = bd["_err"]; return res
    for row in bd.get("series") or []:
        m = row.get("model") or "?"
        for b in row.get("buckets") or []:
            u = b.get("usage") or {}
            req = float(u.get("REQUEST") or 0); rp = float(u.get("RESPONSE_TOKEN") or 0)
            hit = float(u.get("PROMPT_CACHE_HIT_TOKEN") or 0)
            mis = float(u.get("PROMPT_CACHE_MISS_TOKEN") or 0)
            if not (req or rp or hit or mis): continue
            res["req"] += req; res["resp"] += rp; res["hit"] += hit; res["miss"] += mis
            d = res["byModel"].setdefault(m, {"req": 0, "resp": 0, "hit": 0, "miss": 0})
            d["req"] += req; d["resp"] += rp; d["hit"] += hit; d["miss"] += mis
    cache_put(ck, res)
    return res

def summary():
    c = cache_get("summary", 60)
    if c is not None: return c
    bd = api("users/get_user_summary")
    res = {"err": None, "currency": "CNY", "balance": None, "granted": 0.0, "totalCost": None}
    if "_err" in bd:
        res["err"] = bd["_err"]; return res
    nw = (bd.get("normal_wallets") or [{}])[0]
    bw = (bd.get("bonus_wallets") or [{}])[0]
    tc = (bd.get("total_costs") or [{}])[0]
    res["currency"] = nw.get("currency") or "CNY"
    res["balance"] = float(nw.get("balance") or 0)
    res["granted"] = float(bw.get("balance") or 0)
    res["totalCost"] = float(tc.get("amount") or 0)
    cache_put("summary", res)
    return res

# ---------------- 显示 ----------------
def money(v):
    if v is None: return "—"
    return ("¥%.4f" % v) if abs(v) < 1 else ("¥%.2f" % v)

def tk(n):
    n = float(n or 0)
    if n >= 1e9: return "%.2fB" % (n / 1e9)
    if n >= 1e6: return "%.2fM" % (n / 1e6)
    if n >= 1e3: return "%.1fK" % (n / 1e3)
    return "%d" % n

def hm(ts):
    return datetime.datetime.fromtimestamp(ts).strftime("%m-%d %H:%M")

def err_line(e):
    if e == "NO_TOKEN":
        return "✗ 未找到平台 token（~/dsh/.dsp_token）—— 需要在 agent 浏览器里登录 platform.deepseek.com"
    if e and "invalid token" in e.lower():
        return "✗ 平台登录已过期 —— 重新在 agent 浏览器登录 platform.deepseek.com 即可"
    return "✗ " + str(e)

# ---------------- 命令 ----------------
def cmd_report():
    sm = summary()
    print("💰 DeepSeek 账号（官方数据）")
    if sm.get("err"):
        print("   " + err_line(sm["err"]))
    else:
        print("   余额 %s   ｜   累计消费 %s   ｜   %s" % (
            money(sm["balance"]), money(sm["totalCost"]), hm(int(time.time()))))
        if sm.get("granted"):
            print("   赠送余额 %s" % money(sm["granted"]))

    rows = []
    for name in ("today", "yesterday", "month", "d30"):
        c = cost_of(name)
        if c.get("err"):
            print("   ⚠ %s 取数失败：%s" % (c.get("label", name), c["err"])); continue
        a = amount_of(name)
        tok = (a.get("hit", 0) + a.get("miss", 0) + a.get("resp", 0)) if not a.get("err") else 0
        rows.append((c["label"], c["total"], a.get("req", 0) if not a.get("err") else 0, tok))
    if rows:
        print("💵 消费金额")
        for label, cost, req, tok in rows:
            print("   %-7s %-10s %6d 次   %10s tokens" % (label, money(cost), req, tk(tok)))

    t = cost_of("today")
    if not t.get("err") and t["total"] > 0:
        print("🏷 今日分模型")
        for m, v in sorted(t["byModel"].items(), key=lambda x: -x[1]):
            print("   %-40s %s" % (m, money(v)))
        if len(t["byKey"]) > 1 or True:
            print("🔑 今日分 API Key")
            for k, v in sorted(t["byKey"].items(), key=lambda x: -x[1]):
                print("   %-40s %s" % (k, money(v)))
        hs = sorted((int(k), v) for k, v in t["hourly"].items())
        if hs:
            print("⏱ 今日逐小时")
            for h, v in hs:
                bar = "█" * min(30, int(v * 3)) if v > 0 else ""
                print("   %s  %-8s %s" % (
                    datetime.datetime.fromtimestamp(h * 3600).strftime("%H:00"), money(v), bar))

    ps = peak_state()
    pk, off = peak_split(None if t.get("err") else t.get("hourly"))
    print("⚡ 峰谷（工作日 09-12 / 14-18 峰价，周末全天谷价）")
    print("   现在      %s%s   距切换 %s（%s）" % (
        "峰价 ×2" if ps["isPeak"] else "谷价",
        "（周末全谷价）" if ps["weekend"] else "",
        fmt_left(ps["minutesLeft"]),
        datetime.datetime.fromtimestamp(ps["switchAt"]).strftime("%m-%d %H:%M")))
    if pk or off:
        tip = "   ← 峰值那部分挪到谷时可省一半" if pk > 0 else ""
        print("   今日峰时  %-10s 谷时  %s%s" % (money(pk), money(off), tip))

    cf = cfg()
    if cf["DAILY_BUDGET"] or cf["MONTH_BUDGET"]:
        print("🎯 预算")
        for label, cur, bud in (("今日", t.get("total", 0) if not t.get("err") else 0, cf["DAILY_BUDGET"]),
                                ("本月", cost_of("month").get("total", 0), cf["MONTH_BUDGET"])):
            if not bud: continue
            pct = 100.0 * cur / bud
            n = min(20, int(pct / 5))
            print("   %s  %-10s / %-10s %5.0f%%  %s%s" % (
                label, money(cur), money(bud), pct, "█" * n + "░" * (20 - n),
                "  ⚠ 超预算" if pct >= 100 else ("  ⚠ 接近" if pct >= 80 else "")))
    else:
        print("🎯 预算未设置 → 在 ~/dsh/cost-config.sh 里填 DAILY_BUDGET / MONTH_BUDGET")
    # 今日逐小时（24 个桶，本地小时）
    hr = [0.0] * 24
    if not t.get("err"):
        for k, v in (t.get("hourly") or {}).items():
            hr[datetime.datetime.fromtimestamp(int(k) * 3600).hour] += v
    out["hourly"] = [round(x, 4) for x in hr]
    pk_h = max(range(24), key=lambda i: hr[i]) if any(hr) else -1
    out["hourlyPeak"] = {"hour": pk_h, "cost": round(hr[pk_h], 4)} if pk_h >= 0 else None

    dd = daily_map(14)
    if dd:
        tot = sum(dd.values())
        print("📅 近14天 合计 %s ｜ 日均 %s" % (money(tot), money(tot / max(1, len(dd)))))
        print("   %s" % spark(dd))
    return 0

def cmd_period(name):
    c = cost_of(name); a = amount_of(name)
    if c.get("err"): print(err_line(c["err"])); return 1
    print("💵 %s  %s" % (c["label"], money(c["total"])))
    if not a.get("err"):
        hit, mis, rp, req = a.get("hit", 0), a.get("miss", 0), a.get("resp", 0), a.get("req", 0)
        tot = hit + mis + rp
        rate = (100.0 * hit / (hit + mis)) if (hit + mis) else 0
        print("   请求 %d 次 ｜ tokens %s（prompt 命中 %s / 未命中 %s，输出 %s）｜ 缓存命中率 %.1f%%" % (
            req, tk(tot), tk(hit), tk(mis), tk(rp), rate))
    for m, v in sorted(c["byModel"].items(), key=lambda x: -x[1]):
        print("   · %-40s %s" % (m, money(v)))
    return 0

def cmd_hourly(days=1):
    """逐小时（今天 + 前 N-1 天）：金额与 token 各自按小时聚合"""
    for d in range(days - 1, -1, -1):
        s, e = rng(d, 1)
        ck = "cost:%d:%d" % (s, e)
        c = cache_get(ck, ttl_for(e))
        if c is None:
            cost_of("today" if d == 0 else "yesterday")
            c = cache_get(ck, ttl_for(e)) or {}
        a = amount_of("today" if d == 0 else "yesterday")
        print("⏱ %s" % datetime.datetime.fromtimestamp(s).strftime("%m-%d %a"))
        hourly = c.get("hourly") or {}
        amap = {}
        # 按小时取 token：重新拉一次 amount 原始分桶
        r = range_of("today" if d == 0 else "yesterday")
        print("   （金额 %s）" % money(c.get("total", 0)))
        for k in sorted(hourly, key=lambda x: int(x)):
            v = hourly[k]
            hh = datetime.datetime.fromtimestamp(int(k) * 3600).strftime("%H:00")
            bar = "█" * min(int(v * 24), 40)
            print("   %s  %-9s %s" % (hh, money(v), bar))
    return 0

def cmd_keys():
    c = cost_of("today")
    if c.get("err"): print(err_line(c["err"])); return 1
    print("🔑 今日分 API Key（共 %d 个）" % len(c["byKey"]))
    for k, v in sorted(c["byKey"].items(), key=lambda x: -x[1]):
        print("   %-40s %s" % (k, money(v)))
    a = amount_of("today")
    if not a.get("err"):
        print("   请求 %d 次 ｜ tokens %s" % (a.get("req", 0), tk(a["hit"] + a["miss"] + a["resp"])))
    return 0

def daily_map(days=14):
    """近 N 天官方天桶（dict: 时间戳字符串 -> 金额）"""
    s0, e0 = rng(days - 1, days)
    ck = "daily:%d:%d" % (s0, e0)
    d = cache_get(ck, 600)
    if d is None:
        bd = api("usage/by_api_key/cost?start=%d&end=%d&tz=%d" % (s0, e0, tz_off()))
        d = {}
        if "_err" not in bd:
            for blk in bd.get("data") or []:
                for row in blk.get("series") or []:
                    for b in row.get("buckets") or []:
                        k = str(int(b.get("time") or 0))
                        d[k] = d.get(k, 0.0) + float(b.get("cost") or 0)
        cache_put(ck, d)
    return d

def spark(d, width=28):
    """把日金额渲染成一行方块柱状（▁▂▃▄▅▆▇█）"""
    if not d: return ""
    ks = sorted(int(k) for k in d)
    vs = [d[str(k)] for k in ks]
    mx = max(vs) or 1
    blocks = "▁▂▃▄▅▆▇█"
    return "".join(blocks[min(7, int(v / mx * 7.999))] for v in vs)

def cmd_daily(days=30):
    """近 N 天（官方天桶 + 柱状）"""
    d = daily_map(days)
    if not d:
        print("（无数据）"); return 1
    ks = sorted(int(k) for k in d)
    tot = sum(d.values()); mx = max(d.values()) or 1
    print("📅 近 %d 天：合计 %s（日均 %s）" % (len(ks), money(tot), money(tot / len(ks))))
    for k in ks:
        v = d[str(k)]
        bar = "█" * int(round(24.0 * v / mx))
        day = datetime.datetime.fromtimestamp(k)
        mark = "  ← 今天" if day.date() == datetime.date.today() else ""
        print("   %s %s  %-9s %s%s" % (day.strftime("%m-%d"), day.strftime("%a"), money(v), bar, mark))
    print("   趋势 %s" % spark(d))
    return 0

def cmd_peak():
    ps = peak_state(); t = cost_of("today")
    pk, off = peak_split(None if t.get("err") else t.get("hourly"))
    print("⚡ 现在 %s%s" % ("峰价 ×2" if ps["isPeak"] else "谷价",
          "（周末全谷价）" if ps["weekend"] else ""))
    print("   距下次切换 %s → %s" % (fmt_left(ps["minutesLeft"]),
          datetime.datetime.fromtimestamp(ps["switchAt"]).strftime("%m-%d %H:%M")))
    print("   今日 峰时 %s ｜ 谷时 %s" % (money(pk), money(off)))
    return 0

def cmd_json():
    sm = summary()
    out = {"source": "official", "at": datetime.datetime.now().strftime("%m-%d %H:%M"),
           "err": sm.get("err"), "currency": sm.get("currency", "CNY"),
           "balance": sm.get("balance"), "granted": sm.get("granted"),
           "totalCost": sm.get("totalCost"), "periods": {}, "models": {}, "keys": {}}
    for name, key in (("today", "today"), ("yesterday", "yesterday"),
                      ("month", "month"), ("d30", "d30"), ("d7", "d7")):
        c = cost_of(name); a = amount_of(name)
        out["periods"][key] = {
            "cost": round(c.get("total", 0), 4) if not c.get("err") else None,
            "req": a.get("req", 0) if not a.get("err") else 0,
            "hit": a.get("hit", 0) if not a.get("err") else 0,
            "miss": a.get("miss", 0) if not a.get("err") else 0,
            "resp": a.get("resp", 0) if not a.get("err") else 0}
    t = cost_of("today")
    if not t.get("err"):
        out["models"] = {k: round(v, 4) for k, v in t["byModel"].items()}
        out["keys"] = {k: round(v, 4) for k, v in t["byKey"].items()}
    ps = peak_state()
    pk, off = peak_split(None if t.get("err") else t.get("hourly"))
    out["peak"] = {"isPeak": ps["isPeak"], "weekend": ps["weekend"], "minutesLeft": ps["minutesLeft"],
                   "switchAt": ps["switchAt"], "peakCost": round(pk, 4), "offCost": round(off, 4)}
    cf = cfg(); tc = 0 if t.get("err") else t.get("total", 0); mc = cost_of("month").get("total", 0)
    out["budget"] = {"daily": cf["DAILY_BUDGET"], "month": cf["MONTH_BUDGET"],
                     "dailyPct": (round(100.0 * tc / cf["DAILY_BUDGET"], 1) if cf["DAILY_BUDGET"] else None),
                     "monthPct": (round(100.0 * mc / cf["MONTH_BUDGET"], 1) if cf["MONTH_BUDGET"] else None)}
    dd = daily_map(14)
    dk = sorted(int(x) for x in dd)
    out["daily"] = {"days": [{"t": x, "cost": round(dd[str(x)], 4)} for x in dk],
                    "total": round(sum(dd.values()), 4),
                    "avg": round(sum(dd.values()) / max(1, len(dk)), 4)}
    print(json.dumps(out, ensure_ascii=False))

def cmd_sample():
    sm = summary()
    if sm.get("err"): print(err_line(sm["err"])); return 1
    now_ms = time.time() * 1000
    if os.path.exists(BALLOG):
        try:
            last = open(BALLOG).read().strip().split("\n")[-1].split("\t")
            if last and now_ms - int(last[0]) < 30_000:
                return 0
        except Exception:
            pass
    with open(BALLOG, "a") as f:
        f.write("%d\t%.4f\t%.4f\t0\n" % (now_ms, sm["balance"], sm["granted"]))
    return 0

def cmd_refresh():
    """清缓存后立刻重拉（卡片「刷新数据」用）"""
    try:
        os.remove(CACHE)
    except Exception:
        pass
    return cmd_json()

def cmd_check():
    t = token()
    print("token: %s（长度 %d）" % ("存在" if t else "缺失", len(t)))
    sm = summary()
    if sm.get("err"):
        print(err_line(sm["err"])); return 1
    c = cost_of("today")
    print("接口自检 ✅  余额 %s ｜ 累计 %s ｜ 今日 %s" % (
        money(sm["balance"]), money(sm["totalCost"]), money(c.get("total"))))
    return 0

def main():
    a = sys.argv[1:]
    cmd = (a[0] if a else "report").lower()
    if cmd in ("report", ""):        return cmd_report()
    if cmd in ("today", "yesterday", "month", "d30", "30d", "d7", "7d"):
        return cmd_period("today" if cmd == "today" else cmd)
    if cmd == "hourly":              return cmd_hourly(int(a[1]) if len(a) > 1 and a[1].isdigit() else 1)
    if cmd == "keys":                return cmd_keys()
    if cmd == "daily":               return cmd_daily(int(a[1]) if len(a) > 1 and a[1].isdigit() else 30)
    if cmd == "peak":                return cmd_peak()
    if cmd == "models":              return cmd_period("today")
    if cmd == "json":                return cmd_json()
    if cmd in ("sample", "snapshot"): return cmd_sample()
    if cmd in ("check", "预检"):      return cmd_check()
    if cmd in ("refresh", "刷新"):    return cmd_refresh()
    if cmd in ("help", "-h", "--help"):
        print(__doc__); return 0
    print("✗ 未知命令: %s" % cmd); print(__doc__); return 1

sys.exit(main())
PY
