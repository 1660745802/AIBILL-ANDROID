package com.aibill.android.presentation.widget

import android.content.Context
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
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.aibill.android.presentation.MainActivity

/**
 * 快速记账桌面小组件 (2×1)
 * 点击打开 App 手动记账页
 */
class QuickRecordWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .clickable(actionStartActivity<MainActivity>())
                ) {
                    Text(
                        text = "💰",
                        style = TextStyle(fontSize = 24.sp)
                    )
                    Spacer(modifier = GlanceModifier.width(8.dp))
                    Column {
                        Text(
                            text = "快速记账",
                            style = TextStyle(
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = ColorProvider(Color(0xFF009688))
                            )
                        )
                        Text(
                            text = "点击记一笔",
                            style = TextStyle(
                                fontSize = 12.sp,
                                color = ColorProvider(Color.Gray)
                            )
                        )
                    }
                }
            }
        }
    }
}

class QuickRecordWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickRecordWidget()
}
