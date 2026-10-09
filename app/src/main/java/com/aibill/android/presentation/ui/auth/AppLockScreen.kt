package com.aibill.android.presentation.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import com.aibill.android.presentation.theme.PrimaryButtonBlock
import com.aibill.android.presentation.theme.Tokens

/**
 * 应用锁屏。
 *
 * 改造前是一个 64dp 的裸锁图标 + 两行字 + 一个 `SecondaryButton("重新验证")`，
 * 全部硬编码 dp，而且在锁屏这种「用户是被打断后回来看一眼」的场景下，
 * 最重要的信息（为什么被锁住）反而不显眼。
 *
 * 现在：
 * - 锁图标放进 88dp 的主色容器圆形里，形成明确的视觉焦点
 * - 补一句说明「已开启应用锁，返回时需要验证身份」—— 用户才不会再以为 App 崩了
 * - 主按钮改为整宽 `PrimaryButtonBlock`，触控区域达标
 */
@Composable
fun AppLockScreen(
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        showBiometricPrompt(context, onUnlocked)
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Tokens.Spacing.xxxl),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(38.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }

                Spacer(Modifier.size(Tokens.Spacing.xl))

                Text(
                    text = "AiBill 已锁定",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(Modifier.size(Tokens.Spacing.sm))

                Text(
                    text = "你开启了应用锁。完成身份验证后继续记账。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.size(Tokens.Spacing.xxxl))

                PrimaryButtonBlock(
                    text = "重新验证",
                    icon = Icons.Default.Shield,
                    onClick = { showBiometricPrompt(context, onUnlocked) },
                )
            }
        }
    }
}

private fun showBiometricPrompt(context: android.content.Context, onSuccess: () -> Unit) {
    val activity = context as? FragmentActivity ?: return
    val biometricManager = BiometricManager.from(context)
    val canAuth = biometricManager.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    )
    if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
        // 设备不支持生物识别或未设置，直接放行
        onSuccess()
        return
    }

    val executor = ContextCompat.getMainExecutor(context)
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onSuccess()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            // 用户取消或系统错误，保持锁定
        }
    }

    val prompt = BiometricPrompt(activity, executor, callback)
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("身份验证")
        .setSubtitle("验证以解锁 AiBill")
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        .build()

    prompt.authenticate(promptInfo)
}
