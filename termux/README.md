# Termux 侧部署脚本（归档）

App（android-app）之外，宿主机 Termux 里还有这几个脚本在跑。放这里是为了**可复现**：
Termux 重装后照着放回去即可恢复。

| 文件 | 放哪里 | 作用 |
|---|---|---|
| `aria2-ctl.sh` | `~/dsh/` | Aria2 离线下载控制：start/stop/restart/status/json/ensure/log/info/secret/add/add-ext/add-b64 |
| `ariang-ctl.sh` | `~/dsh/` | AriaNg 网页控制台（纯静态）：start/stop/status/ensure/url |
| `boot-start-aria2.sh` | `~/.termux/boot/` | 开机自启 Aria2 —— **默认不装**（可选，见下方「关于开机自启」）|
| `boot-start-ariang.sh` | `~/.termux/boot/` | 开机自启 AriaNg —— **默认不装**（可选）|

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
- **自动下发**：`aria2-ctl.sh` / `ariang-ctl.sh` 已随 App 打包（`assets/`），
  由 `DshApi.bootstrapTools()` 版本门控下发到 `~/dsh/` —— 改脚本 bump `TOOLS_VER` 即可送达
- **不再有看门狗**：v0.6.4 起移除了常驻守护（它持有 `termux-wake-lock` 持续耗电）。
  现在 Aria2 / AriaNg / OpenList / dsh 全部**手动按需启停**，`ensure` 子命令仅保留给手动调用

## 关于开机自启（重要）

**默认全部关闭** —— 本项目的设计原则是「要用的时候打开，不用的时候关掉」。

原因：每个开机自启脚本都会调用 `termux-wake-lock`，而 wake-lock 会**阻止 CPU 休眠**，
常驻后台持续耗电。v0.6.4 起连 dsh 的看门狗也一并移除了。

| 服务 | 开机自启 | 如何开启 |
|---|---|---|
| dsh | ❌ 默认关 | 部署时加 `--with-boot`（`dsh-oneclick.sh`）|
| OpenList | ❌ 默认关 | App 抽屉 → OpenList → 「开机自启」开关 |
| Aria2 | ❌ 默认关 | 手动把 `boot-start-aria2.sh` 拷到 `~/.termux/boot/` |
| AriaNg | ❌ 默认关 | 手动把 `boot-start-ariang.sh` 拷到 `~/.termux/boot/` |

> **副作用**：重启手机后所有服务**不会自动起来**，需要打开 App 手动点一下「启动」。
> 这是刻意的取舍 —— 换来的是后台零常驻、零 wake-lock。

开启开机自启的前置：安装 **Termux:Boot** 并手动打开它一次（完成开机广播注册）。

已停用的脚本会集中存放在 `~/dsh/boot-disabled/`，随时可以拷回 `~/.termux/boot/` 恢复。

## 一个坑（原始文件名）

网盘直链常把真名放在 URL 查询参数里（`?filename=xxx`），而 Aria2 只认 `Content-Disposition` 或 URL 路径 →
会存成 `c-m9019` 这种路径名。所以加任务时**显式传 `out`**（见 `add-ext`），App 的分享通道就是这么做的。
