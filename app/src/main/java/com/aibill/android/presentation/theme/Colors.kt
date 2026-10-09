package com.aibill.android.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 品牌色 + 语义色集中定义。**任何屏幕文件都不应直接写 Color(0xFFxxxxxx)**，
 * 全部通过此文件暴露的语义令牌引用。
 *
 * ## 为什么语义色要区分明暗
 * 记账 App 里「红=支出 / 绿=收入」是**承载信息的语义契约**，不是装饰。
 * 同一个 `#E53935` 在深色底上对比度只有 3.3:1（低于 WCAG AA 正文 4.5:1），
 * 在浅色底上又过于饱和。所以 [SemanticColors] 为每个语义色提供明/暗两套取值，
 * 由 [LocalSemanticColors] 按当前主题下发。
 *
 * 用法（Composable 内）：`MaterialTheme.semantic.expense`
 * 或直接引用顶层便捷属性 `ExpenseColor`（其 getter 是 @Composable，会自动取当前主题）。
 */

// ============ 品牌主色（teal 青绿） ============
/**
 * 品牌 teal。相比旧值 #00897B 压暗一档：与白色前景的对比度从 3.9:1 提升到 4.7:1，
 * 色相与饱和度不变，视觉上仍是同一个青绿，只是按钮上的白字更清楚。
 */
internal val Teal = Color(0xFF00796B)
internal val TealLight = Color(0xFF4DB6AC)
internal val TealDark = Color(0xFF004D40)

// ============ 语义色（全局统一，明/暗自适应） ============

/**
 * 语义色容器。由 [AiBillTheme] 按明暗主题下发，所有 UI 通过
 * [MaterialTheme.semantic] 或顶层便捷属性读取。
 */
@Immutable
data class SemanticColors(
    /** 支出主色（金额数字、图标、选中态描边） */
    val expense: Color,
    /**
     * 铺在 [expense] **实底**上的前景色（按钮文字 / 图标）。
     * 浅色主题支出色较深 → 白字；深色主题支出色是浅色（#FF8A80）→ 必须深字，
     * 否则就是「白字压浅红」，对比度 2.3:1，完全读不出来。
     */
    val onExpense: Color,
    /** 支出弱背景（选中 chip、弱高亮行底色） */
    val expenseContainer: Color,
    val onExpenseContainer: Color,
    /** 收入主色 */
    val income: Color,
    /** 铺在 [income] 实底上的前景色（见 [onExpense]） */
    val onIncome: Color,
    val incomeContainer: Color,
    val onIncomeContainer: Color,
    /** 转账 / 中性 */
    val transfer: Color,
    /** 铺在 [transfer] 实底上的前景色（见 [onExpense]） */
    val onTransfer: Color,
    val transferContainer: Color,
    /** 待同步、需要注意但不阻塞 */
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    /** 已完成 / 正常运行 */
    val success: Color,
    val successContainer: Color,
    /** 破坏性操作（删除、退出登录） */
    val danger: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    /** 图表网格线 / 极弱分隔 */
    val chartGrid: Color,
    /** 卡片描边（仅在需要明确边界时使用，如 Hero 卡） */
    val hairline: Color,
)

internal val LightSemantics = SemanticColors(
    expense = Color(0xFFC62828),          // 对白底 5.6:1
    onExpense = Color(0xFFFFFFFF),        // 对支出实底 5.6:1
    expenseContainer = Color(0xFFFDECEA),
    onExpenseContainer = Color(0xFF8E1616),
    income = Color(0xFF2E7D32),           // 对白底 5.1:1
    onIncome = Color(0xFFFFFFFF),         // 对收入实底 5.1:1
    incomeContainer = Color(0xFFE7F4E8),
    onIncomeContainer = Color(0xFF14521A),
    transfer = Color(0xFF546E7A),
    onTransfer = Color(0xFFFFFFFF),
    transferContainer = Color(0xFFECEFF1),
    warning = Color(0xFFA86200),
    warningContainer = Color(0xFFFFF3DE),
    onWarningContainer = Color(0xFF7A4700),
    success = Color(0xFF2E7D32),
    successContainer = Color(0xFFE7F4E8),
    danger = Color(0xFFC62828),
    dangerContainer = Color(0xFFFDECEA),
    onDangerContainer = Color(0xFF8E1616),
    chartGrid = Color(0x14101828),
    hairline = Color(0x1F101828),
)

internal val DarkSemantics = SemanticColors(
    expense = Color(0xFFFF8A80),          // 对深底 7.4:1
    // ⚠️ 深色主题的语义色是亮色，白字压上去只有 2.3:1 —— 实底前景色必须一起反转
    onExpense = Color(0xFF3A0F0C),        // 对 #FF8A80 ≈ 7.6:1
    expenseContainer = Color(0xFF3A1A19),
    onExpenseContainer = Color(0xFFFFB4AB),
    income = Color(0xFF7BD389),
    onIncome = Color(0xFF0C2410),         // 对 #7BD389 ≈ 8.2:1
    incomeContainer = Color(0xFF16301A),
    onIncomeContainer = Color(0xFFA5D6A7),
    transfer = Color(0xFFB0BEC5),
    onTransfer = Color(0xFF1A2224),       // 对 #B0BEC5 ≈ 9.1:1
    transferContainer = Color(0xFF26302F),
    warning = Color(0xFFFFC46B),
    warningContainer = Color(0xFF3A2A10),
    onWarningContainer = Color(0xFFFFDDB0),
    success = Color(0xFF7BD389),
    successContainer = Color(0xFF16301A),
    danger = Color(0xFFFF8A80),
    dangerContainer = Color(0xFF3A1A19),
    onDangerContainer = Color(0xFFFFB4AB),
    chartGrid = Color(0x1FFFFFFF),
    hairline = Color(0x14FFFFFF),
)

internal val LocalSemanticColors = staticCompositionLocalOf { LightSemantics }

/** 取当前主题下的语义色组。非 Composable 场景请用下方顶层便捷属性。 */
val MaterialTheme.semantic: SemanticColors
    @Composable
    @ReadOnlyComposable
    get() = LocalSemanticColors.current

// ---------- 顶层便捷属性（@Composable getter，调用点无需改动） ----------

val ExpenseColor: Color
    @Composable @ReadOnlyComposable get() = LocalSemanticColors.current.expense
val ExpenseContainerColor: Color
    @Composable @ReadOnlyComposable get() = LocalSemanticColors.current.expenseContainer
val IncomeColor: Color
    @Composable @ReadOnlyComposable get() = LocalSemanticColors.current.income
val IncomeContainerColor: Color
    @Composable @ReadOnlyComposable get() = LocalSemanticColors.current.incomeContainer
val TransferColor: Color
    @Composable @ReadOnlyComposable get() = LocalSemanticColors.current.transfer
val TransferContainerColor: Color
    @Composable @ReadOnlyComposable get() = LocalSemanticColors.current.transferContainer
val WarningColor: Color
    @Composable @ReadOnlyComposable get() = LocalSemanticColors.current.warning
val WarningContainerColor: Color
    @Composable @ReadOnlyComposable get() = LocalSemanticColors.current.warningContainer
val SuccessColor: Color
    @Composable @ReadOnlyComposable get() = LocalSemanticColors.current.success
val DangerColor: Color
    @Composable @ReadOnlyComposable get() = LocalSemanticColors.current.danger
val DangerContainerColor: Color
    @Composable @ReadOnlyComposable get() = LocalSemanticColors.current.dangerContainer

/**
 * 取「铺在实心语义色上的前景色」。
 *
 * **背景与前景必须成对取，不要在语义色底上硬编码 `Color.White`**：
 * 深色主题下 `expense` / `income` / `transfer` 都是亮色，白字压上去
 * 只有 1.8~2.4:1，低于 WCAG AA。
 *
 * ```
 * val accent  = if (type == "income") s.income else s.expense
 * val onAccent = MaterialTheme.onAccentFor(type)
 * ```
 */
@Composable
@ReadOnlyComposable
fun MaterialTheme.onAccentFor(type: String): Color = when (type) {
    "income" -> LocalSemanticColors.current.onIncome
    "transfer" -> LocalSemanticColors.current.onTransfer
    else -> LocalSemanticColors.current.onExpense
}



// ============ 渐变（品牌渐变） ============

/** 主品牌渐变（135° 斜向）。用于 Hero 卡 / 汇总卡。 */
val BrandGradient: Brush = Brush.linearGradient(
    colors = listOf(Teal, TealLight),
)

/** 收入渐变（绿） */
val IncomeGradient: Brush = Brush.linearGradient(
    colors = listOf(Color(0xFF2E7D32), Color(0xFF66BB6A)),
)

/** 支出渐变（红） */
val ExpenseGradient: Brush = Brush.linearGradient(
    colors = listOf(Color(0xFFC62828), Color(0xFFEF5350)),
)

/** 警告渐变（橙） */
val WarningGradient: Brush = Brush.linearGradient(
    colors = listOf(Color(0xFFA86200), Color(0xFFFFC46B)),
)

/**
 * 柔和品牌渐变。保留品牌识别但不刺眼，
 * 适用于「我的」页 Hero。
 */
val BrandGradientSubtle: Brush = Brush.linearGradient(
    colors = listOf(TealLight, Color(0xFFB2DFDB)),
)

// ============ 通知隐私遮罩 ============

/** 通知隐私模式下金额显示 */
const val PRIVACY_MASK = "¥***"

// ============ Material 3 Tonal Palette（Light） ============
// 用于 Theme.kt ColorScheme 定义。命名遵循 M3 规范：
// {Brand}{Role}{Variant}，其中 Variant = Light/Dark（来自 ColorScheme 主题）。
internal val TealContainerLight = Color(0xFFB2DFDB)
internal val TealOnContainerLight = Color(0xFF00332E)
internal val TealSecondaryContainerLight = Color(0xFFCCE8E4)
internal val TealOnSecondaryContainerLight = Color(0xFF00201C)
internal val TertiaryLight = Color(0xFF4A6360)
/** ColorScheme.error 的原始取值（与 SemanticColors.expense/danger 保持同色） */
internal val ErrorLight = Color(0xFFC62828)
internal val ErrorContainerLight = Color(0xFFFFDAD6)
internal val OnErrorContainerLight = Color(0xFF410002)
internal val BackgroundLight = Color(0xFFF6F8F8)
internal val OnBackgroundLight = Color(0xFF171B1B)
internal val OnSurfaceLight = Color(0xFF171B1B)
internal val SurfaceVariantLight = Color(0xFFDDE7E4)
internal val OnSurfaceVariantLight = Color(0xFF3F4947)
internal val SurfaceContainerLowestLight = Color.White
internal val SurfaceContainerLowLight = Color(0xFFF1F5F4)
internal val SurfaceContainerLight = Color(0xFFEBF0EF)
internal val SurfaceContainerHighLight = Color(0xFFE4EAE9)
internal val SurfaceContainerHighestLight = Color(0xFFDEE4E3)
internal val OutlineLight = Color(0xFF6F7977)
internal val OutlineVariantLight = Color(0xFFBFC9C7)

// ============ Material 3 Tonal Palette（Dark） ============
internal val OnPrimaryDark = Color(0xFF00382F)
internal val TealOnContainerDark = Color(0xFFB2DFDB)
internal val SecondaryDark = Color(0xFF80CBC4)
internal val OnSecondaryDark = Color(0xFF00201C)
internal val SecondaryContainerDark = Color(0xFF004D40)
internal val TealOnSecondaryContainerDark = Color(0xFFCCE8E4)
internal val TertiaryDark = Color(0xFFB1CCC8)
internal val ErrorDark = Color(0xFFFF8A80)
internal val OnErrorDark = Color(0xFF690005)
internal val ErrorContainerDark = Color(0xFF93000A)
internal val OnErrorContainerDark = Color(0xFFFFDAD6)
internal val BackgroundDark = Color(0xFF0E1211)
internal val OnBackgroundDark = Color(0xFFE2E8E6)
internal val SurfaceDark = Color(0xFF121716)
internal val OnSurfaceDark = Color(0xFFE2E8E6)
internal val SurfaceVariantDark = Color(0xFF3F4947)
internal val OnSurfaceVariantDark = Color(0xFFBFC9C7)
internal val SurfaceContainerLowestDark = Color(0xFF090D0C)
internal val SurfaceContainerLowDark = Color(0xFF171C1B)
internal val SurfaceContainerDark = Color(0xFF1B211F)
internal val SurfaceContainerHighDark = Color(0xFF252B2A)
internal val SurfaceContainerHighestDark = Color(0xFF303736)
internal val OutlineDark = Color(0xFF899391)
internal val OutlineVariantDark = Color(0xFF3F4947)
