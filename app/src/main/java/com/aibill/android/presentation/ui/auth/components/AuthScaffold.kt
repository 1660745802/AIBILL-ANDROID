package com.aibill.android.presentation.ui.auth.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CurrencyYen
import androidx.compose.material3.Icon
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aibill.android.presentation.theme.Tokens

/**
 * 认证页统一骨架（登录 / 注册 / 服务器配置共用）。
 *
 * ## 改造要点
 * 改造前三个页面各自手写居中 Column + `emoji("💰"/"🎉")` 大字 + 硬编码
 * 36.dp/20.dp 间距，三个页面的品牌头部长得都不一样。
 *
 * 现在：
 * - 品牌标记用**矢量图标装在 72dp 的圆角方块里**（`¥` 符号），
 *   emoji 在不同 Android 厂商 ROM 上字形/基线/颜色都不同，不适合做品牌资产
 * - 头部、间距、表单宽度全部收敛到一处
 * - 内容可滚动 + `imePadding`：软键盘弹出时表单不会被顶掉，
 *   且小屏机型可以滚动看到「注册」按钮
 * - 表单限宽 360dp 并水平居中：平板/折叠屏展开时不会拉成一整行难以阅读
 */
@Composable
fun AuthScaffold(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    /** 顶部是否显示品牌标记。服务器配置页是技术配置页，不需要。 */
    showBrandMark: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = {
            snackbarHostState?.let { SnackbarHost(it) }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(
                        horizontal = Tokens.Spacing.xxxl,
                        vertical = Tokens.Spacing.xxl,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (showBrandMark) {
                    BrandMark()
                    Spacer(Modifier.height(Tokens.Spacing.lg))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Tokens.Spacing.xs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Tokens.Spacing.xxxl))
                content()
            }
        }
    }
}

/**
 * 品牌标记：72dp 圆角方块 + 品牌渐变 + `¥` 矢量图标。
 *
 * 比 emoji 稳定（不随系统字体/厂商变化），并且天然带品牌色，
 * 在登录页这种「品牌第一印象」的位置更值得投资源。
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(72.dp)
            .clip(RoundedCornerShape(Tokens.Radius.xl))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.CurrencyYen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(38.dp),
        )
    }
}

/**
 * 认证页底部的次要链接（如「没有账号？点击注册」「⚙️ 配置服务器」）。
 *
 * 改造前 LoginScreen 里写的是 `"⚙️ 配置服务器"`，emoji 和文字混排；
 * 现在 emoji 换成矢量图标，文字单独排版。
 */
@Composable
fun AuthFooterLink(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.sm))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = Tokens.Spacing.md, vertical = Tokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Tokens.IconSize.sm),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}
