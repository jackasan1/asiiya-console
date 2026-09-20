# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与 [语义化版本](https://semver.org/lang/zh-CN/)。

## [0.6.1] — 2026-09-20

**性能、安全与工程质量优化**

### Performance
- ⚡ **轮询开销大幅下降**：`dsh-ctl.sh` 实际 22.7 KB（base64 后约 30 KB），
  而 `bootstrap()` 原本**每次调用都重新下发并写盘**（注释里写的"约 4 KB"早已过时）。
  改为「版本标记 + 文件存在性」双重判断，与 `dsh-cost.sh` 的做法对齐 —— 只在首次安装 / 脚本升级时下发一次。
  在 5 秒轮询下：**每 5 秒省掉 30 KB Intent + 22.7 KB 磁盘写入 + 一次无意义写入**。
- ⏱ **自适应轮询退避**：状态连续无变化时 5s → 10s → 20s → 30s 逐级退避；
  一旦状态变化或用户操作，立刻回到 5s。
- 💰 费用拉取由「每 6 个 tick」改为**按真实时间每 30 秒**，避免退避后触发时机漂移。
- 🎨 `SparkView.onDraw` 不再每根柱 `new RectF()`，消除绘制期对象分配。

### Security
- 🔒 **WebView 收紧**：禁用 file / content 访问、禁止跨源 file 请求、强制不混入明文；
  站外链接改由系统浏览器打开，避免在被信任的本地 WebView 里被带走。
- 🌐 **网络安全配置**：由全局 `android:usesCleartextTraffic="true"` 改为 `network_security_config`，
  **仅对 `127.0.0.1` / `localhost` / `::1` 放行明文**，其余域名强制 HTTPS。

### Build
- 📦 **开启 R8 代码压缩 + 资源压缩**：release APK **5.05 MB → 1.73 MB（−65.7%）**。
  配套 `proguard-rules.pro`：保留自定义 View 构造函数、WidgetProvider、ViewBinding 静态入口。
- 🔧 修正仓库改名后遗留的 3 处硬编码地址（`deepseek-harness-android` → `asiiya-console`）。

### Accessibility
- ♿ 17 个纯装饰图标由 `contentDescription="@null"` 改为 `importantForAccessibility="no"`，
  TalkBack 不再把它们读成无标签控件。

### Quality
- 🧪 **新增 13 个单元测试**：`FormattersTest`（金额 / token / 时长格式化）、
  `DshApiTest`（status 与 cost 的 JSON 解析回归，覆盖前置 banner、字段缺失等场景）。
- 🚦 CI 增加 **`testDebugUnitTest` + `lintDebug` 门禁**（此前只构建、不校验）。
- 🧹 抽出 `Formatters.kt`，把纯函数从 2100 行的 `MainActivity` 中解耦（也让它们可被单测覆盖）。
- 🐛 **单测发现的真实缺陷**：余额为 0 时显示 `¥0.0000`，现修正为 `¥0.00`。
- 🎨 lint：21 处 `android:tint` → `app:tint`（AppCompat 兼容染色），**lint error 归零**。

---

## [0.6.0] — 2026-09-20

**「柔光层叠 / Lumen Soft」视觉重构** —— 只换设计语法，不动信息架构与业务逻辑。

### Added
- 🎨 新增 `ShadowLayout`：自绘柔和分层阴影容器（12 层圆角矩形平方衰减），
  解决 Android XML 无法表达「上亮下暗」双向阴影的问题。零第三方依赖，兼容 API 26。
- ✨ 新增 `UiMotion` 动效工具集：
  - 按压反馈：`scale 1→0.96` + `OvershootInterpolator` 弹簧回弹
  - 卡片错峰入场：`translationY 14dp→0` + 淡入，60ms 错峰
  - 金额滚动：`ValueAnimator` 480ms（保留 `<¥1 显示 4 位小数` 的原有格式）
  - 状态色缓动、系统动画开关检测（无障碍友好）
- 🖼️ 新增图标底板渐变：`bg_circle_green`（网盘）、`bg_circle_violet`（下载）
- 📄 新增 `docs/design/` 设计文档与 `docs/screenshots/` 真机截图

### Changed
- 🌗 **配色整体重构**（深浅双模）：
  - 底色由冷蓝黑 `#080B11` → 暖深灰 `#0F1116`；浅色由冷灰 `#E9EDF4` → 暖纸 `#F6F5F2`
  - 强调色由 `#4F8BF7` → 薰衣草蓝 `#6B7CFF`
  - 语义色（ok/warn/bad）统一降饱和 ~12%
- 📐 **尺寸**：卡片圆角 `22dp → 28dp`、指标块 `16dp → 18dp`、
  按钮由 16dp 圆角改为**全圆角胶囊**、新增 `ico_radius` / `card_pad` / `shadow_blur` / `shadow_dy`
- 🔤 **字阶**：新增等宽数字（`tnum`）避免刷新时宽度跳动；大数字 letterSpacing −0.035em
- 🃏 **卡片材质**：实心面 + 顶部高光渐变 + 1dp 发丝描边 + `ShadowLayout` 柔和阴影
- ⭕ **图标底板**：圆形 → 14dp 圆角方块，半透明色 → 对角渐变，字形统一深色 `ico_fg`
- 🛑 **危险操作克制化**：`停止` 按钮由实心红改为「中性面 + 红色发丝边 + 红字」，
  避免破坏性操作比主操作更抢眼

### Fixed
- 修正按钮按下反馈过硬（无回弹）的问题，现在统一走 `UiMotion.pressable()`

---

## [0.5.0] 及更早

- Aria2 离线下载整链 + 费用卡增强 + 桌面小组件
- 抽屉改为一级项目目录 → 二级操作台
- Android/Termux 兼容修复（node-pty / koffi / 硬链接 / sharp 等）
