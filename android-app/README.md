# DSH 控制台 (dsh-console)

给 [deepseek-harness-android](../) 配套的 Android 控制 App。

## 能做什么

| 按钮 | 作用 |
|---|---|
| 环境体检 | 检查 Termux / node / dsh / allow-external-apps / termux-api |
| 安装 dsh | 把内置的 `dsh-oneclick.sh`（base64 注入）写进 `~/dsh/` 并执行 |
| 启动 | `setsid bash ~/dsh/dsh-watchdog.sh`（含看门狗，挂了自动拉起） |
| 停止 | 停看门狗 + `pkill` dsh web（真正停掉） |
| 打开界面 | 读取 `~/dsh/dsh-web-url.txt`（含 token）并用浏览器打开 |
| 刷新状态 | 服务/守护运行状态 + dsh 版本 + 当前地址 |

## 原理

通过 `com.termux.RUN_COMMAND` Intent 在 Termux 里执行 shell 并回收 stdout / exitCode。

**不需要 root。**

## 前置条件

1. 装 [Termux](https://github.com/termux/termux-app/releases)（GitHub / F-Droid 版，**别用 Play 商店版**）
2. Termux 里执行一次并重启 Termux：
   ```bash
   mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties
   ```
3. （建议）给 Termux 开「显示在其他应用上层」和关闭电池优化

## 构建

推送到 GitHub 后由 Actions 自动构建，产物在 **Actions → 对应 run → Artifacts**。

本地构建：`./gradlew assembleDebug`

## 签名说明

产物是 **debug 签名**的 APK，可直接安装。若要发布正式版，需自行配置 release keystore。
