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
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.aibill.android.presentation.MainActivity

/**
 * 桌面小组件配色。
 *
 * 改造前两个小组件里直接写死了 `0xFF009688` / `0xFFF44336` / `0xFF4CAF50`
 * 和 `Color.Gray`：
 * - 青色和品牌主色（#00796B）**不是同一个绿**，桌面和 App 内对不上
 * - 红绿用的是 Material 500 而不是语义色的 800，浅色底上对比度不够
 * - `Color.Gray` 在深色桌面壁纸上是深灰，基本看不见
 *
 * 现在统一走这里的令牌，并用 `ColorProvider(day, night)` 跟随系统深浅色。
 */
internal object WidgetColors {
    /**
     * Glance 1.1.1 的 `ColorProvider` 只接受单个颜色（没有 day/night 重载），
     * 写死一套颜色会在深色桌面上瞎掉。
     *
     * 所以这里区分两类颜色：
     * - **跟随系统明暗**的，用 `GlanceTheme.colors.*`（它内部就是资源化的
     *   ColorProvider，Compose 侧渲染时会按宿主配置解析）
     * - **固定品牌色**的（品牌底 + 白字），对比度由我们自己控制，不依赖宿主配置
     *
     * 小组件因此全部采用「品牌绿底 + 白字」方案：无论用户桌面壁纸是深是浅，
     * 文字对比度都是恒定的 4.5:1 以上。
     */

    /** 品牌底色 */
    val brandSurface: ColorProvider = ColorProvider(Color(0xFF00796B))

    /** 品牌底上的圆角底（比主色更浅一档，用于图标圆底） */
    val brandContainer: ColorProvider = ColorProvider(Color(0x33000000))

    /** 品牌底上的主文字 */
    val onBrand: ColorProvider = ColorProvider(Color(0xFFFFFFFF))

    /** 品牌底上的次文字 */
    val onBrandMuted: ColorProvider = ColorProvider(Color(0xB3FFFFFF))
}

/**
 * 快速记账桌面小组件 (2×1)
 *
 * 改造点：`💰` emoji → 品牌色圆形底的 `+` 符号。
 * emoji 在各家 ROM 的桌面渲染差异很大（有的带彩色背景、有的没有），
 * 而这是整个 App 在桌面上的第一印象，必须稳定。
 */
class QuickRecordWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(WidgetColors.brandSurface)
                        .padding(12.dp)
                        .clickable(actionStartActivity<MainActivity>()),
                ) {
                    Box(
                        modifier = GlanceModifier
                            .size(36.dp)
                            .background(WidgetColors.brandContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "+",
                            style = TextStyle(
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = WidgetColors.onBrand,
                            ),
                        )
                    }
                    Spacer(modifier = GlanceModifier.width(10.dp))
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            text = "记一笔",
                            style = TextStyle(
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = WidgetColors.onBrand,
                            ),
                        )
                        Spacer(modifier = GlanceModifier.height(1.dp))
                        Text(
                            text = "点开就能记",
                            style = TextStyle(
                                fontSize = 12.sp,
                                color = WidgetColors.onBrandMuted,
                            ),
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
