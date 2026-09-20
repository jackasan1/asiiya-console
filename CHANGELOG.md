# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与 [语义化版本](https://semver.org/lang/zh-CN/)。

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
