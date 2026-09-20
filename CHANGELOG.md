# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与 [语义化版本](https://semver.org/lang/zh-CN/)。

## [0.6.3] — 2026-09-21

**三张卡片交互统一**

用户反馈：dsh 卡的交互（状态字「启动中…」+ 图标转一圈）很好，但
**OpenList 网盘**和 **Aria2 离线下载**两张卡没有 —— 点了按钮毫无反馈，像是没点上。

### Root Cause

- dsh 卡有 `ringBusy()`（图标转 360° + 脉冲）和 `pendingAction`（乐观状态）
- OL / Aria2 卡的点击处理**只有一行 `action(...)`** —— 既无图标动效，也无挂起态
- 两张卡的图标 `ImageView` 甚至连 `id` 都没有，动效无从施加

### Changed

- 🔧 把 dsh 卡那套交互**抽成三卡通用能力**，不再各写一套：

| 能力 | 说明 |
|---|---|
| `pending: Map<String, String>` | 三张卡各自的动作挂起态（`dsh` / `ol` / `aria`） |
| `iconBusy(view)` | 图标转一圈 650ms + 脉冲放大（原 `ringBusy` 泛化） |
| `stateLabelText(card, up)` | 状态文案：挂起态优先显示「启动中… / 停止中…」（琥珀色） |
| `stateLabelColor(card, up)` | 颜色：挂起中 = `warn`，运行时 = `ok`，停止 = `dim` |
| `beginPending(card, kind)` / `endPending(card)` | 进入 / 退出挂起态，20 余处逻辑统一 |
| `setCardBusy(card, busy)` | 挂起期间禁用该卡动作按钮，避免重复点击 |

- 🖼 `card_openlist.xml` 图标补 `@+id/ivOlIcon`
- 🖼 `card_aria.xml` 图标补 `@+id/ivAriaIcon`
- 🎛 网盘「安装」（未安装时该按钮为安装）**不计入**启动挂起态，避免误导
- 🛟 三张卡共用同一套收敛规则：实况与预期一致即恢复，25 秒超时兜底

### 效果

现在三张卡点击后行为**完全一致**：

```
点下 → 图标转一圈 + 脉冲
     → 状态徽标立刻变「启动中…」/「停止中…」（琥珀色）
     → 该卡动作按钮暂时禁用
     → 就绪/退出后自动翻成「运行中」/「已停止」，按钮恢复
```

### 验证
- [x] `assembleDebug` / `assembleRelease` 编译通过，无 error
- [x] 单元测试 / lint 全绿
- [x] 现有 dsh 卡行为保持不变（回归）

---

## [0.6.2] — 2026-09-21

**启动 / 停止的响应速度优化**

用户反馈「点启动或停止要等好几秒」。定位后发现是**两件事叠加**：

### 根因

| 环节 | 问题 |
|---|---|
| 脚本 | `stop` 里写死了 `sleep 1` + `sleep 2` —— **无条件硬睡 3 秒** |
| 脚本 | 所有就绪轮询粒度都是 **2 秒**（服务 0.5 秒就绪也要等到下一次轮询） |
| 脚本 | `start` 会**阻塞到服务完全就绪**才返回，而 dsh 是 Node 应用，真实启动约 **10 秒** |
| App | 动作期间状态徽标**保持旧值**，要等回执 + 下一次 5 秒轮询才更新 |

实测：`stop` 3.10 秒，`start` 10.18 秒，且期间界面**毫无反馈**。

### Changed — 脚本（`assets/dsh-ctl.sh`）

- ⚡ **`stop` 去掉硬睡**：改为「条件满足立即返回」——等看门狗真正退出、等残留进程真正消失，
  实测 **3.10s → 1.34s（−57%）**
- ⏱ **轮询粒度 2s → 0.2s**：`wait_ready` / start 与 open 的 URL 轮询 / `ol_wait` / `ol_sv_wait`
  （总超时时间不变，只是更早发现就绪）
- 去掉 `restart` 与 `openlist-restart` 的固定 `sleep 2`，同样改为条件轮询
- `CTL_VER` 8 → 9（App 侧同步 bump，老用户会自动重新下发脚本）

### Changed — App（乐观 UI）

- ✨ **点下即反馈**：点「启动/停止」的瞬间，状态徽标立刻切成
  **「启动中…」/「停止中…」**（琥珀色），不再让用户对着旧状态干等
- ⏳ 挂起期间动作按钮暂时禁用，避免重复点击
- 🚀 **动作回执到达后立刻拉一次状态**（350ms），而不是干等下一个轮询周期
- 🔁 **挂起期间轮询提到 1 秒档**，服务一就绪立刻翻成「运行中」
- 🛟 25 秒超时兜底 + 「实况与预期一致即收敛」，避免状态卡住

> 关于 `start` 的 10 秒：这是 dsh（Node 应用）的**真实启动耗时**，
> 无法通过脚本优化掉。试过加 `nowait` 非阻塞模式，收益只有约 0.9 秒且带来
> 进程生命周期风险，已放弃 —— **正确的解法是让界面立刻给反馈**。

### 新增字符串
- `state_stopping`（停止中 / Stopping）

### 验证
- [x] 脚本 `bash -n` 语法通过
- [x] 前后对照实测：stop 3.10s → 1.34s
- [x] 单元测试 / lint / assembleRelease 全绿

---

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
