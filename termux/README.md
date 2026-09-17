# Termux 侧部署脚本（归档）

App（android-app）之外，宿主机 Termux 里还有这几个脚本在跑。放这里是为了**可复现**：
Termux 重装后照着放回去即可恢复。

| 文件 | 放哪里 | 作用 |
|---|---|---|
| `aria2-ctl.sh` | `~/dsh/` | Aria2 离线下载控制：start/stop/restart/status/json/ensure/log/info/secret/add/add-ext/add-b64 |
| `ariang-ctl.sh` | `~/dsh/` | AriaNg 网页控制台（纯静态）：start/stop/status/ensure/url |
| `boot-start-aria2.sh` | `~/.termux/boot/` | 开机自启 Aria2 |
| `boot-start-ariang.sh` | `~/.termux/boot/` | 开机自启 AriaNg |

## 依赖与前置

```bash
pkg install aria2          # Aria2 本体
mkdir -p ~/aria-ng         # AriaNg：下载 AllInOne 单文件版解压到这里（index.html）
```

Aria2 配置在 `~/.aria2/aria2.conf`（关键项）：

```
enable-rpc=true
rpc-listen-all=false        # 只监听 127.0.0.1（安全）
rpc-listen-port=6800
rpc-secret=<32 位随机串>
rpc-allow-origin-all=true   # 必开：不加的话网页端跨域连不上
dir=/sdcard/Download/aria2
file-allocation=none        # 手机别用预分配
```

## 与 App / OpenList 的关系

- App 的「DSH 控制台 / Asiiya 工作台」通过 `dsh-ctl.sh` 的 `aria2-*` 子命令调用（`status` 里带 `aria*` 字段）
- OpenList 的离线下载指向同一个 Aria2（设置键 `aria2_uri` / `aria2_secret`）
- 看门狗 `dsh-watchdog.sh` 每 60 秒对两个脚本各执行一次 `ensure`，掉了自动拉起

## 一个坑（原始文件名）

网盘直链常把真名放在 URL 查询参数里（`?filename=xxx`），而 Aria2 只认 `Content-Disposition` 或 URL 路径 →
会存成 `c-m9019` 这种路径名。所以加任务时**显式传 `out`**（见 `add-ext`），App 的分享通道就是这么做的。
