package com.aibill.android.presentation.ui.auth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibill.android.presentation.theme.AppTextButton
import com.aibill.android.presentation.theme.PrimaryButtonBlock
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.ui.auth.components.AuthFooterLink
import com.aibill.android.presentation.ui.auth.components.AuthScaffold
import com.aibill.android.presentation.ui.auth.components.AuthTextField

/**
 * 登录页。
 *
 * 视觉骨架走 [AuthScaffold]，与注册页 / 服务器配置页保持同一套品牌头部。
 * 「配置服务器」从原来飘在页面最底下的一个 `AppTextButton("⚙️ 配置服务器")`
 * 改成表单下方的整行入口：emoji 换成矢量图标，触控区域也从 ~32dp 变成 48dp。
 */
@Composable
fun LoginScreen(
    viewModel: AuthViewModel = hiltViewModel(),
    onNavigateToHome: () -> Unit = {},
    onNavigateToRegister: () -> Unit = {},
    onNavigateToServerConfig: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is AuthViewModel.UiEvent.NavigateToHome -> onNavigateToHome()
                is AuthViewModel.UiEvent.ShowError ->
                    snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    val canSubmit = username.isNotBlank() && password.isNotBlank()

    AuthScaffold(
        title = "AiBill",
        subtitle = "说一句、收一条通知，账就记好了",
        snackbarHostState = snackbarHostState,
    ) {
        AuthTextField(
            value = username,
            onValueChange = { username = it },
            label = "用户名",
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Next,
            enabled = !uiState.isLoading,
        )

        Spacer(Modifier.height(Tokens.Spacing.lg))

        AuthTextField(
            value = password,
            onValueChange = { password = it },
            label = "密码",
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            isPassword = true,
            enabled = !uiState.isLoading,
            onDone = { if (canSubmit) viewModel.login(username, password) },
        )

        Spacer(Modifier.height(Tokens.Spacing.xxl))

        PrimaryButtonBlock(
            text = "登录",
            onClick = { viewModel.login(username, password) },
            enabled = canSubmit && !uiState.isLoading,
            loading = uiState.isLoading,
        )

        Spacer(Modifier.height(Tokens.Spacing.sm))

        AppTextButton(
            text = "还没有账号？去注册",
            onClick = onNavigateToRegister,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(Tokens.Spacing.lg))

        AuthFooterLink(
            text = "配置服务器地址",
            icon = Icons.Default.Dns,
            onClick = onNavigateToServerConfig,
        )
    }
}

/**
 * 注册页。
 *
 * 头部从 `emoji("🎉")` 换成品牌标记 —— 注册页不需要自己的吉祥物，
 * 保持和登录页同一套品牌识别，用户切换页面时不会有「换了个 App」的错觉。
 * 「需要邀请码才能注册」从副标题提到字段 label 上，让用户在输入前就知道。
 */
@Composable
fun RegisterScreen(
    viewModel: AuthViewModel = hiltViewModel(),
    onRegisterSuccess: () -> Unit = {},
    onNavigateBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is AuthViewModel.UiEvent.NavigateToHome -> onRegisterSuccess()
                is AuthViewModel.UiEvent.ShowError ->
                    snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    val canSubmit = username.isNotBlank() && password.isNotBlank() && inviteCode.isNotBlank()

    AuthScaffold(
        title = "创建账号",
        subtitle = "需要邀请码，注册后即可开始记账",
        snackbarHostState = snackbarHostState,
    ) {
        AuthTextField(
            value = username,
            onValueChange = { username = it },
            label = "用户名",
            imeAction = ImeAction.Next,
            enabled = !uiState.isLoading,
        )

        Spacer(Modifier.height(Tokens.Spacing.lg))

        AuthTextField(
            value = password,
            onValueChange = { password = it },
            label = "密码",
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Next,
            isPassword = true,
            enabled = !uiState.isLoading,
        )

        Spacer(Modifier.height(Tokens.Spacing.lg))

        AuthTextField(
            value = inviteCode,
            onValueChange = { inviteCode = it },
            label = "邀请码",
            imeAction = ImeAction.Next,
            enabled = !uiState.isLoading,
        )

        Spacer(Modifier.height(Tokens.Spacing.lg))

        AuthTextField(
            value = nickname,
            onValueChange = { nickname = it },
            label = "昵称（选填）",
            imeAction = ImeAction.Done,
            enabled = !uiState.isLoading,
            onDone = { if (canSubmit) viewModel.register(username, password, inviteCode, nickname) },
        )

        Spacer(Modifier.height(Tokens.Spacing.xxl))

        PrimaryButtonBlock(
            text = "注册",
            onClick = { viewModel.register(username, password, inviteCode, nickname) },
            enabled = canSubmit && !uiState.isLoading,
            loading = uiState.isLoading,
        )

        Spacer(Modifier.height(Tokens.Spacing.md))

        AppTextButton(
            text = "已有账号？去登录",
            onClick = onNavigateBack,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
