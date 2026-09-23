package com.aibill.android.presentation.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibill.android.presentation.components.AppTopBar
import com.aibill.android.presentation.theme.PrimaryButton
import com.aibill.android.presentation.ui.auth.components.AuthTextField

/**
 * 注册页。**已重构**：4 个 OutlinedTextField → AuthTextField × 4。
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = { AppTopBar(title = "注册", onBack = onNavigateBack) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = "🎉", fontSize = 48.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "创建账号",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "需要邀请码才能注册",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(36.dp))

            AuthTextField(
                value = username,
                onValueChange = { username = it },
                label = "用户名",
                imeAction = ImeAction.Next,
                enabled = !uiState.isLoading,
            )

            Spacer(Modifier.height(16.dp))

            AuthTextField(
                value = password,
                onValueChange = { password = it },
                label = "密码",
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next,
                isPassword = true,
                enabled = !uiState.isLoading,
            )

            Spacer(Modifier.height(16.dp))

            AuthTextField(
                value = inviteCode,
                onValueChange = { inviteCode = it },
                label = "邀请码",
                imeAction = ImeAction.Next,
                enabled = !uiState.isLoading,
            )

            Spacer(Modifier.height(16.dp))

            AuthTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = "昵称（选填）",
                imeAction = ImeAction.Done,
                enabled = !uiState.isLoading,
            )

            Spacer(Modifier.height(36.dp))

            PrimaryButton(
                text = "注册",
                onClick = {
                    viewModel.register(
                        username = username.trim(),
                        password = password,
                        inviteCode = inviteCode.trim(),
                        nickname = nickname.trim().ifBlank { null },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isLoading,
                loading = uiState.isLoading,
            )
        }
    }
}
