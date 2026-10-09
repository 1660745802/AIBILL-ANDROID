# UI 设计规范

> 适用范围：`presentation/` 下所有 Compose 代码。
> **改 UI 前先读这一篇**，不要在页面里硬编码 `.dp` / `Color(0x…)` / emoji 图标。

配套文档：[ARCHITECTURE.md](ARCHITECTURE.md)（分层与数据流）、[CONTRIBUTING.md](CONTRIBUTING.md)（编码规范）。

---

## 一、为什么要写这份文档

重构前这个项目里同时存在：

| 问题 | 表现 |
|---|---|
| 卡片圆角四套 | 16 / 18 / 20 / 24dp 混用，"卡片"在用户眼里不是一个统一单位 |
| chip 高度两套 | M3 `FilterChip`(32dp/8dp圆角) 和手写 `Row`(34dp/16dp圆角) 并排，肉眼对不齐 |
| 大量 emoji 当图标 | `📭 🔍 📊 😥 😌 🔔 💰 🎉 🔗 💡 ⚡ 🟢 🔴` —— 跨 ROM 字形/基线/配色全不一致 |
| 语义色不随深色模式 | `ExpenseColor=#E53935` 在深色底上对比度只有 3.3:1，低于 WCAG AA 正文 4.5:1 |
| 阴影当层级 | `CardElevation` 到处写不同值，视觉噪音大 |
| 硬编码散落 | 各页 `20.dp` / `16.dp` 手写，`Tokens` 形同虚设 |

本文档 + `presentation/theme/` + `presentation/components/` 就是为了消灭上面每一项。

---

## 二、设计令牌（唯一来源：`presentation/theme/`）

### 2.1 尺寸 `Tokens`

```kotlin
Tokens.Spacing     // hair2 xxs4 xs4 sm8 md12 lg16 xl20 xxl24 xxxl32 huge48
                   // screenHorizontal=20  screenBottomWithFab=108  screenBottom=32
                   // cardPadding=24  cardPaddingSm=16  listItemVertical=12
Tokens.Radius       // none0 xs6 sm10 md14 lg18 xl24 xxl28 pill100
Tokens.IconSize     // xs14 sm18 md22 lg28 xl36 xxl48
Tokens.TouchTarget  // small40 normal48 large52 xlarge56
Tokens.Avatar       // sm28 md36 lg42 xl56 hero64
Tokens.Elevation    // 页面卡片全为 0（用色调分层）；sheet3 fab6 dialog8
Tokens.Border       // hairline0.5 thin1 thick2
Tokens.Chip         // height32 heightLarge36 paddingHorizontal12 iconSize14
Tokens.List         // dividerIndent76 dateHeaderHeight40
Tokens.Keypad       // keyHeight56 gap12
Tokens.Chart        // canvasHeight160 barThickness6 progressHeight4
Tokens.Motion       // DURATION_INSTANT90 FAST150 MEDIUM240 SLOW380
```

**层级 = 圆角递增**：行/输入框/按钮 `sm~md`(10~14) → 卡片 `lg`(18) → 汇总卡 `xl`(24) → Sheet `xxl`(28)。

### 2.2 语义色

```kotlin
val s = MaterialTheme.semantic   // 自动跟随明暗，不受动态取色影响
s.expense / s.expenseContainer / s.onExpenseContainer
s.income  / s.incomeContainer  / s.onIncomeContainer
s.transfer / s.transferContainer
s.warning / s.warningContainer / s.onWarningContainer
s.success / s.danger / s.dangerContainer / s.onDangerContainer
s.chartGrid / s.hairline
```

顶层便捷属性（Composable 内可直接用）：`ExpenseColor` `IncomeColor` `TransferColor`
`WarningColor` `SuccessColor` `DangerColor` 及各自的 `*ContainerColor`。

> ⚠️ 这些是 `@Composable` getter，**只能在 Composable 里用**。非 Composable 上下文
> （比如工具函数里的默认值）请改用 `MaterialTheme.colorScheme.error`。

**为什么语义色必须区分明暗**：记账 App 里「红=支出 / 绿=收入」是**承载信息的语义契约**，
不是装饰。同一个红在深色底上只有 3.3:1 对比度，在浅色底上过饱和。

### 2.3 排版

```kotlin
MaterialTheme.typography   // 完整 M3 15 档，已调中文行高

AmountTypography.Hero      // 36sp Bold  等宽 —— 首页月度支出，全 App 只出现一次
AmountTypography.Large     // 26sp Bold  等宽
AmountTypography.Input     // 40sp Bold  等宽 —— 记账页金额
AmountTypography.Row       // 17sp Bold  等宽 —— 列表金额
AmountTypography.Stat      // 15sp SemiBold 等宽
AmountTypography.Chip      // 13sp SemiBold 等宽

AppTextStyles.SectionLabel // 13sp SemiBold 字距 .6sp
AppTextStyles.ListTitle    // 16sp Medium
AppTextStyles.ListSubtitle // 13sp Normal
```

**所有金额必须走 `AmountTypography.*`**，因为它们开了 `tnum`（等宽数字）。
比例数字会让 `¥1,111.11` 和 `¥888.88` 里的 `1` 比 `8` 窄，同一列金额左右跳动，
用户无法竖着对齐比较 —— 这是记账类应用最容易被忽略却最影响可用性的细节。

### 2.4 动效

`Tokens.Motion` 的四个时长。**只用于回应用户动作的动画**（按下、展开、保存成功、页面转场）。
不要给每一行加入场淡入上滑这类装饰性动效。

---

## 三、共享组件（`presentation/components/`）

**页面只做组合，不重复造轮子。**

### 3.1 容器 `AppSurfaces.kt`

```kotlin
AppCard(onClick, containerColor = surfaceContainerLow) { /* ColumnScope */ }
AppHeroCard(onClick, containerColor = primaryContainer, contentColor, border) { }
SectionHeader(title, subtitle, action)
GroupedList { }                 // 一块卡 + 内部若干行
GroupedDivider()                // 已处理缩进对齐
GroupedRow(title, subtitle, icon, leadingEmoji, onClick, trailing, showChevron)
Pill(text, tone = PillTone.*, icon)   // 状态胶囊
StatCell(label, value, valueStyle, showDividerBefore)
```

`GroupedRow` 的 `leadingEmoji`：分类 / 账户在本项目里就是用 **emoji 字符串**表示的
（`Category.icon` / `Account.icon`），传了它会覆盖矢量 `icon`。

### 3.2 Chip `AppChips.kt`

```kotlin
AppChip(selected, onClick, label, accent, leadingIcon)   // 选中显示 ✓
AppInfoChip(label, accent, leadingIcon)                  // 只读
RemovableFilterChip(label, onRemove, accent)             // 点一下即取消筛选
FilterTriggerChip(label, activeCount, onClick, icon)
ChipRow(...) { } / FlowChips(...) { }
```

**所有 chip 高度恒定 32dp、圆角恒定 pill**，不允许再手写 `RoundedCornerShape(16.dp)` 的 Row。

### 3.3 状态 `States.kt`

```kotlin
EmptyState(title, icon, subtitle, actionText, onAction)
SearchEmptyState(keyword, onClear)
ErrorState(title, subtitle, icon, actionText, onAction)
LoadingState / AppendLoading / AppendError(onRetry)
```

> `emoji` 参数已在本次重构中删除，只接受矢量 `icon`。
> 空状态是**邀请行动**，不是告知没数据。

### 3.4 金额 `AmountDisplay.kt`

```kotlin
AmountText(amount, type, privacy, style = AmountTypography.Row, showSign)
AmountHero(amount, type)        // ¥ 符号小一号 + 数字全尺寸
AmountHeadline(amount, type)
TrendIndicator(percent, inverted)   // ↑↓ + 百分比
AmountFormatter.toYuanDisplay(fen)  // ¥1,234.00（千分位）
AmountFormatter.toCompactDisplay(fen)
AmountFormatter.toAxisLabel(fen)   // 轴标签，超千折算「万」
```

### 3.5 列表行 / 分段控件 / 按钮

```kotlin
TransactionRow(transaction, onClick, highlight, showTime)
CategoryAvatar(icon, modifier, background, iconSize)
TypeSegmentedControl(selected, onSelected, options)   // 支出红/收入绿，轨道+指示块
SegmentedControl(selected, onSelected, options)       // 中性版
CategoryPickerItem(icon, label, selected, onClick)
PrimaryButton / PrimaryButtonBlock / SecondaryButton / AppOutlinedButton
AppTextButton / DangerButton
```

---

## 四、页面设计约定

### 4.1 卡片预算

**一个页面里"重量级"容器最多 2~3 个。**

- 列表项 → 裸行 + 分隔线，或整组一个 `GroupedList`（**不要每条一张卡**）
- 页面级汇总 → 一张 `AppHeroCard`
- 其余分组 → `GroupedList`

### 4.2 阴影

M3 的层级靠**色调分层**表达。页面卡片一律 `Tokens.Elevation.low`（= 0dp），
投影只留给 FAB / BottomSheet / Dialog。

### 4.3 空 / 错 / 加载态

每个列表页和详情页都必须覆盖三种状态，且都走 `States.kt` 的组件。
`when` 分支要互斥，注意别把错误分支写死导致永远显示不出来。

### 4.4 页面级信息优先级

参考首页与统计页：每屏只回答一个问题。

| 页面 | 第一眼 | 第二眼 | 第三眼 |
|---|---|---|---|
| 首页 | 本月支出总额 | 今日流水 | 单笔明细 |
| 统计 | 当期总额 + 环比 | 趋势 / 构成 | 分类排行 |
| 记账 | 金额 | 分类 | 标签备注 |

---

## 五、动态取色默认关闭

`UserPreferences.dynamicColorEnabled` 默认值已从 `true` 改为 `false`。

原因：跟随壁纸取色会把 `primary`（品牌青绿）换成用户壁纸里的任意颜色，
而 `primary` 与「支出红 / 收入绿」是一整套配套的语义契约。一旦取色撞上，
就会出现「主按钮和支出金额同色」「选中 chip 和危险操作同色」这类语义冲突。

语义色（`MaterialTheme.semantic`）**始终跟随明暗，不受动态取色影响** ——
支出的含义不能被壁纸改掉。

用户仍可在 设置 → 外观 手动开启。

---

## 六、常见误区

| 别做 | 要做 |
|---|---|
| `padding(horizontal = 20.dp)` | `Tokens.Spacing.screenHorizontal` |
| `Color(0xFF...)` | `MaterialTheme.colorScheme.*` 或 `MaterialTheme.semantic.*` |
| `Text("📊")` 当图标 | `Icon(Icons.Outlined.BarChart, ...)` |
| `fontSize = 28.sp` 当金额 | `AmountTypography.*` |
| 每条流水一个 `Card` | `GroupedList` 内的 `TransactionRow` |
| `EmptyState(emoji = ...)` | `EmptyState(icon = ...)` |
| 按钮常驻可点 + 点完弹 Toast | 真实禁用态 + 说明缺什么 |
| 状态只用颜色区分 | 颜色 + 图标 + 字重三重编码 |
| 登录/记账页用系统键盘输入金额 | 底部锚定自绘键盘（拇指可达 + 布局不跳动） |

---

## 七、改完自查

- [ ] `./gradlew compileDebugKotlin --offline`（**必须 JDK 17**：`export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`）
- [ ] `./gradlew detekt --offline`
- [ ] 全仓库无新增 emoji 图标、无硬编码 `.dp`、无硬编码 `Color(0x…)`
- [ ] 明色 + 深色两套都成立
- [ ] 空 / 错 / 加载三态齐全
- [ ] 所有可点区域 ≥ 48dp
- [ ] **业务逻辑、ViewModel、Repository、导航路由未被改动**（UI 重构的边界）