# Asiiya 工作台 · 方向 C「柔光层叠 Lumen Soft」Token 细化

> 目标：**只换设计语法，不动信息架构与业务逻辑**
> 项目：`android-app`（dsh-console，Kotlin + ViewBinding + MaterialComponents.DayNight）
> 生成日期：2026-09-20 · 预览页：`/sdcard/Download/dsh-ui-directions.html`

---

## 一、Color Token 映射（保持 token 名不变，只换值）

### values-night/colors.xml（深色）

| token | 现值 | 新值 (Lumen Soft) | 说明 |
|---|---|---|---|
| `bg` | `#FF080B11` | `#FF0F1116` | 深灰偏暖，弃用蓝黑纯色 |
| `surface` | `#FF12161D` | `#FF1B1F28` | 卡片基色（叠 gradient → `#FF15181F`）|
| `surface2` | `#FF1A212B` | `#FF232833` | 卡内次级块 |
| `stroke` | `#13FFFFFF` | `#11FFFFFF` | 1dp 描边更轻 |
| `fg` | `#FFEEF3FA` | `#FFEDEFF5` | 主文字 |
| `fg2` | `#FF9BA7BA` | `#FF98A0B2` | 次级文字 |
| `dim` | `#FF6C7789` | `#FF7D879B` | 弱化文字 |
| `accent` | `#FF4F8BF7` | `#FF6B7CFF` | 主强调：薰衣草蓝 |
| `accent2` | `#FF3ED8A4` | `#FF5FD8A8` | |
| `ok` | `#FF3ED8A4` | `#FF5FD8A8` | |
| `bad` | `#FFFB7185` | `#FFFF8497` | |
| `warn` | `#FFFFB74D` | `#FFFFC46B` | 降饱和 |
| `accent_from` | `#FF5FD8EA` | `#FFB7C4FF` | 渐变起点 |
| `accent_to` | `#FF4F8BF7` | `#FF7C8CFF` | 渐变终点 |
| `track` | `#FF1E2530` | `#FF232833` | |
| `chip` | `#FF1A212B` | `#FF21252F` | |
| `card` | `#FF12161D` | `#FF1B1F28` | |
| `violet` | `#FFA78BFA` | `#FFB9A6FF` | |
| `cyan` | `#FF22D3EE` | `#FF7FE3F5` | |

### values/colors.xml（浅色 · 暖纸底，不是冷灰）

| token | 现值 | 新值 | 说明 |
|---|---|---|---|
| `bg` | `#FFE9EDF4` | `#FFF6F5F2` | **暖白**，与深色同族 |
| `surface` | `#FFFFFFFF` | `#FFFFFFFF` | |
| `surface2` | `#FFF2F5FA` | `#FFF8F8F5` | |
| `stroke` | `#170F172A` | `#0F14161C` | |
| `fg` | `#FF0F1722` | `#FF14161C` | |
| `fg2` | `#FF5B6779` | `#FF6C7284` | |
| `dim` | `#FF8A96A8` | `#FF9BA1B0` | |
| `accent` | `#FF3B7DF0` | `#FF5B6CFF` | |
| `ok` | `#FF0FA57A` | `#FF1CA078` | |
| `bad` | `#FFE11D48` | `#FFE2536B` | |
| `warn` | `#FFE8912A` | `#FFE09A3E` | |
| `accent_from` | `#FF4FC3E8` | `#FFB7C4FF` | |
| `accent_to` | `#FF3B7DF0` | `#FF7C8CFF` | |

> 浅色不是简单反色：底用暖白、语义色统一降饱和 ~12%，保证与深色**同一设计语法**。

---

## 二、Dimen Token

| token | 现值 | 新值 | 说明 |
|---|---|---|---|
| `card_radius` | 22dp | **28dp** | 差异化核心 |
| `block_radius` | 16dp | **18dp** | |
| `pill_radius` | 13dp | **100dp** | 全圆角 |
| `btn_h` | 52dp | 52dp | 保持 |
| `grid_h` | 72dp | 68dp | |
| `space_m` | 14dp | 14dp | 保持 |
| `icon_box` | 52dp | 34dp | 对齐卡头图标实际尺寸 |
| **新增** `card_pad` | — | 16dp | 卡片内边距 |
| **新增** `ico_radius` | — | 14dp | 图标底板圆角 |
| **新增** `press_scale` | — | 0.96 | 按压缩放 |

---

## 三、字阶（values/styles.xml）

| 样式 | 现值 | 新值 |
|---|---|---|
| `TopbarTitle` | 19sp bold | **17.5sp / w700** |
| `CardTitle` | 17sp bold | **15sp / w700** |
| `CardSub` | 11.5sp | **11sp** |
| `BigNumber` | 34sp bold | **33sp / w800 / letterSpacing -0.035em** |
| `Value` | 15sp bold | **13sp / w700** |
| `Label` | 12sp | **10sp / 大写 / letterSpacing +0.07em** |
| `Pill` | 12.5sp bold | **10.5sp / w700** |

> 数字统一 `fontFeatureSettings="tnum"`（等宽数字），避免刷新时宽度跳动。

---

## 四、材质规格（drawable）

| 文件 | 规格 |
|---|---|
| `bg_card` | 28dp 圆角。layer-list：实心 `surface` → 顶部 1dp 渐变高光 `#14FFFFFF→transparent` → 1dp 描边 `stroke` |
| `bg_block` | 18dp 圆角。`#14FFFFFF→#05FFFFFF` 微渐变 + 1dp `#0EFFFFFF` |
| `bg_btn_primary` | 全圆角。`accent_from→accent_to` 线性渐变（135°）|
| `bg_btn_ghost` | 全圆角。`#0EFFFFFF` 实心 + 1dp `#0FFFFFFF` 描边 |
| `bg_btn_danger` | 全圆角。`bad` @15% + 文字 `bad` |
| `bg_pill` | 全圆角。语义色 @15%（复用已有 `tint_*` / `ok_bg`）|
| `bg_progress` | 全圆角，`track` 底 + `accent` 填充 |
| **新增** `bg_ico_blue` | 14dp 圆角，`accent_from→accent_to` 渐变，图标深色 |
| **新增** `bg_ico_green` | 14dp 圆角，`#7BEBC4→#3FC99A` |
| **新增** `bg_ico_violet` | 14dp 圆角，`#D0BEFF→#A98CFF` |
| **新增** `bg_topbar_btn` | 14dp 圆角。`#1E222B→#181B23` + 1dp `#0FFFFFFF` |
| **新增** `bg_topbar_btn_circle` | 100dp 圆角，同上材质 |

### ⚠️ 关键实现注意点：Android XML 没有阴影

设计里的"柔和分层阴影"落地必须三选一：

1. **`elevation` + `spotShadowColor`（API 28+）** — 最省事，但阴影是系统黑，无法做出"上亮下暗"的双向阴影
2. **layer-list 伪阴影（推荐）** — 用外层 1dp 半透明描边 + 略大一圈的圆角矩形模拟，**零性能开销、兼容 API 26**
3. **自定义 `ShadowLayout` / 9-patch** — 最还原，但要写 View

> 建议：主卡片用方案 2，弹层（sheet）用方案 1+2 组合。

---

## 五、动效规格（全部可用原生 API 落地）

| 场景 | 实现 | 参数 |
|---|---|---|
| 按钮按压 | `ViewPropertyAnimator` + `SpringAnimation` | 按下 `scale 1→0.96` 90ms `Decelerate`；松手 `Spring(damping .35, stiffness 400)` |
| 卡片入场 | `ViewPropertyAnimator` + `startDelay` | `translationY 14dp→0` + `alpha 0→1`，260ms `Decelerate`，错峰 60ms |
| 状态胶囊切换 | `ValueAnimator.ofArgb` | 底色/文字色插值 160ms `FastOutSlowIn` |
| 费用数字滚动 | `ValueAnimator` + `DecimalFormat` | 480ms `Decelerate`，保持 2 位小数 |
| 弹层上滑 | `SpringAnimation(translationY)` | `damping .8 / stiffness 300`；遮罩 `alpha 0→.62` 200ms |
| 成功反馈 | `ViewPropertyAnimator` | `scale 0.8→1.05→1` 320ms + 涟漪 `scale .6→1.5` alpha→0 1200ms（一次性）|
| 环形进度 | 沿用 `RingView` | 加 `ValueAnimator(0→pct)` 600ms `Decelerate` |
| 折线图 | 沿用 `SparkView` | 加 `PathMeasure` 描边动画 600ms |

> 缓动统一：进场 `DecelerateInterpolator`，交互反馈 `SpringAnimation`，状态切换 `FastOutSlowInInterpolator`。

---

## 六、改动范围清单

**改（视觉层）**
- `res/values/colors.xml`、`res/values-night/colors.xml` — 换值
- `res/values/dimens.xml` — 圆角 / 尺寸
- `res/values/styles.xml` — 字阶 + 按钮/胶囊样式
- `res/drawable/*.xml` — 重做 12~14 个背景 drawable
- `res/layout/*.xml` — 卡片/按钮引用与层级调整
- `MainActivity.kt` — **仅新增动效代码**（按压、入场、数字滚动）

**不动（业务层）**
- `DshApi.kt`、`TermuxRunner.kt`、`InstallService.kt`、`App.kt`
- `AndroidManifest.xml`、`xml/*`、`RingView/SparkView` 的**绘制逻辑**（仅加动画驱动）

---

## 七、验收标准

- [ ] 深浅双模下，所有卡片/按钮/胶囊对比度 ≥ 4.5:1
- [ ] 数字刷新无宽度跳动（等宽数字生效）
- [ ] 按压反馈在 100ms 内可见
- [ ] 卡片入场错峰总时长 ≤ 600ms
- [ ] 无新增第三方依赖
- [ ] APK 体积增幅 < 200KB
