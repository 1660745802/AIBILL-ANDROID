package com.aibill.android.presentation.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.aibill.android.presentation.MainActivity
import com.aibill.android.service.WidgetDataUpdater

/**
 * 月度摘要桌面小组件 (4×2)
 * 显示本月支出/收入/结余，点击打开 App 首页
 * 数据通过 WidgetDataUpdater (DataStore) 预缓存
 */
class MonthlySummaryWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // 从 DataStore 读取缓存的月度数据
        val expenseCents = WidgetDataUpdater.getMonthlyExpense(context)
        val incomeCents = WidgetDataUpdater.getMonthlyIncome(context)
        val balanceCents = incomeCents - expenseCents

        val expenseText = formatCents(expenseCents)
        val incomeText = formatCents(incomeCents)
        val balanceText = formatCents(balanceCents)

        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .clickable(actionStartActivity<MainActivity>()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "本月收支",
                        style = TextStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = ColorProvider(Color(0xFF009688))
                        )
                    )

                    Spacer(modifier = GlanceModifier.height(8.dp))

                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        SummaryItem("支出", expenseText, Color(0xFFF44336))
                        SummaryItem("收入", incomeText, Color(0xFF4CAF50))
                        SummaryItem("结余", balanceText, Color(0xFF009688))
                    }
                }
            }
        }
    }

    private fun formatCents(cents: Int): String {
        val absYuan = kotlin.math.abs(cents) / 100.0
        val prefix = if (cents < 0) "-¥" else "¥"
        return "$prefix${"%.2f".format(absYuan)}"
    }
}

@Composable
private fun SummaryItem(label: String, amount: String, color: Color) {
    Column(
        modifier = GlanceModifier.padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 11.sp, color = ColorProvider(Color.Gray))
        )
        Spacer(modifier = GlanceModifier.height(2.dp))
        Text(
            text = amount,
            style = TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = ColorProvider(color),
                textAlign = TextAlign.Center,
            )
        )
    }
}

class MonthlySummaryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MonthlySummaryWidget()
}
