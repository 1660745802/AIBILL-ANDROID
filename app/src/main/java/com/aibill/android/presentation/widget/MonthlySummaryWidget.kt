package com.aibill.android.presentation.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.aibill.android.presentation.MainActivity
import com.aibill.android.service.WidgetDataUpdater
import java.util.Locale

/**
 * 月度摘要桌面小组件 (4×2)
 *
 * 改造前是「支出 / 收入 / 结余」三个等权重数字横排 —— 桌面上用户余光一扫，
 * 三个数字一样大、一样重要，等于没有重点。
 *
 * 现在按 App 内首页的同一套层级重排：**支出是主角**（最大字号），
 * 收入和结余降级成下方两格辅助指标。桌面上的信息优先级和 App 里保持一致。
 */
class MonthlySummaryWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val expenseCents = WidgetDataUpdater.getMonthlyExpense(context)
        val incomeCents = WidgetDataUpdater.getMonthlyIncome(context)
        val balanceCents = incomeCents - expenseCents

        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        // 品牌底 + 白字：对比度不随桌面壁纸明暗变化
                        .background(WidgetColors.brandSurface)
                        .padding(16.dp)
                        .clickable(actionStartActivity<MainActivity>()),
                    verticalAlignment = Alignment.Top,
                ) {
                    // 上下文：和 App 里一样，说明这是「本月」
                    Text(
                        text = "本月支出",
                        style = TextStyle(
                            fontSize = 12.sp,
                            color = WidgetColors.onBrandMuted,
                        ),
                    )
                    Spacer(modifier = GlanceModifier.height(2.dp))

                    // 主角：支出
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "¥",
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = WidgetColors.onBrand,
                            ),
                        )
                        Text(
                            text = yuanDigits(expenseCents),
                            style = TextStyle(
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Bold,
                                color = WidgetColors.onBrand,
                            ),
                        )
                    }

                    Spacer(modifier = GlanceModifier.height(10.dp))

                    // 辅助：收入 / 结余
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        MiniStat(
                            label = "收入",
                            amount = compact(incomeCents),
                            color = WidgetColors.onBrand,
                            modifier = GlanceModifier.defaultWeight(),
                        )
                        MiniStat(
                            label = if (balanceCents >= 0) "结余" else "超支",
                            amount = compact(kotlin.math.abs(balanceCents)),
                            color = WidgetColors.onBrandMuted,
                            modifier = GlanceModifier.defaultWeight(),
                        )
                    }
                }
            }
        }
    }

    /**
     * 分 → "1,234.50"（不含 ¥，由调用方单独渲染小号的货币符号）。
     *
     * ⚠️ 负数必须**先取符号再取绝对值**：`-50` 分的整数除是 `0`（没有负号），
     * 直接 `cents / 100` 会得到 "0.50"，符号凭空丢失。当前只用于恒非负的
     * 月支出，但这是定时炸弹，这里直接把负数分支补上。
     */
    private fun yuanDigits(cents: Int): String {
        val negative = cents < 0
        val abs = Math.abs(cents)
        return (if (negative) "-" else "") + String.format(
            Locale.US,
            "%,d.%02d",
            abs / 100,
            abs % 100,
        )
    }

    /** 分 → "¥1,234"（去掉小数，桌面空间有限） */
    private fun compact(cents: Int): String =
        "¥" + String.format(Locale.US, "%,d", cents / 100)
}

/**
 * 小组件里的次级指标格。
 * 与 App 内 [com.aibill.android.presentation.components.StatCell] 保持同样的
 * 「上 label / 下 value」结构，桌面和 App 里读起来是同一套语言。
 */
@Composable
private fun MiniStat(
    label: String,
    amount: String,
    color: ColorProvider,
    modifier: GlanceModifier = GlanceModifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = TextStyle(fontSize = 11.sp, color = WidgetColors.onBrandMuted),
        )
        Spacer(modifier = GlanceModifier.height(1.dp))
        Text(
            text = amount,
            style = TextStyle(
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = color,
            ),
        )
    }
}

class MonthlySummaryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MonthlySummaryWidget()
}
