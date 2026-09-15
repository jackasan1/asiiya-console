# DSH 控制台 · dsh-console

配套 [deepseek-harness-android](../) 的 Android 控制 App。
在手机上完成 dsh 的**安装 / 启动 / 停止 / 状态查看 / 界面访问**，**不需要 root**。

## 架构

```
┌─────────────── Android App ───────────────┐
│ MainActivity      主控台（状态卡 + 按钮 + 日志）
│ ConsoleActivity   内嵌 WebView，直接显示 dsh 界面
│ DshApi            解析 dsh-ctl.sh 输出的 JSON
│ TermuxRunner      封装 Termux RUN_COMMAND Intent
└───────────────────────┬───────────────────┘
                        │ com.termux.RUN_COMMAND
                        ▼
┌──────────── Termux 侧 ~/dsh/dsh-ctl.sh ────┐
│ status | start | stop | open | install    │
│ log | preflight                            │
└───────────────────────────────────────────┘
```

**通信协议**：`dsh-ctl.sh status` 输出一行 JSON——这是 App 与 Termux 之间唯一的状态契约。

```json
{"service":"UP","watchdog":"UP","port":"UP","portCode":"401",
 "dshVersion":"0.1.5-rc.1","install":"IDLE","ctlVersion":3,
 "url":"http://127.0.0.1:3080/?token=..."}
```

App 侧检查 `ctlVersion` 判断协议兼容性。

## 功能

| 按钮 | 行为 |
|---|---|
| 环境体检 | 运行 `dsh-ctl.sh preflight`，逐项检查依赖与权限 |
| 安装 dsh | 用 base64 注入内置的 `dsh-oneclick.sh` 并执行；API Key 走 **stdin**（不进进程列表） |
| 启动 | `dsh-ctl.sh start`（含看门狗，挂了自动拉起） |
| 停止 | `dsh-ctl.sh stop`（连看门狗一起停） |
| 打开界面 | `dsh-ctl.sh open` → 就绪后在**内嵌 WebView** 打开 |
| 刷新状态 | 解析 JSON 更新状态卡 |

- **自动刷新**：前台时定时轮询状态
- **执行中禁用按钮**，避免并发调用
- **深色/浅色主题**跟随系统；中英文双语

## 前置条件

1. 装 [Termux](https://github.com/termux/termux-app/releases)（GitHub / F-Droid 版，**别用 Play 商店版**）
2. Termux 里执行一次后**重启 Termux**：
   ```bash
   mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties
   ```
3. 给 App 授予 `com.termux.permission.RUN_COMMAND`（首次启动会弹窗，或
   `设置 → 应用 → DSH 控制台 → 权限 → 额外权限`）

## 构建

推送到 `main` 后由 GitHub Actions 自动构建 **release 签名 APK**。

- 工作流：`.github/workflows/android.yml`
- 产物：Actions → 对应 run → Artifacts → `dsh-console-release`

### 签名

使用固定密钥库，保证**每次构建签名一致**（可覆盖安装）。
密钥库以 base64 存在仓库 Secrets 里：

| Secret | 说明 |
|---|---|
| `ANDROID_KEYSTORE_B64` | base64 编码的密钥库 |
| `ANDROID_KEYSTORE_PASSWORD` | 库口令 |
| `ANDROID_KEY_ALIAS` | 别名 |
| `ANDROID_KEY_PASSWORD` | 别名口令 |

> 生成方式见 `.github/workflows/gen-keystore.yml`（一次性工作流，用完即删）。
> ⚠️ 密钥库丢失后，将无法再给已安装的 App 推送更新。
