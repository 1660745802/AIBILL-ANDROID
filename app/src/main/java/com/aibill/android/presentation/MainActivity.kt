package com.aibill.android.presentation

import android.app.ActivityManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aibill.android.data.remote.interceptor.AuthEvent
import com.aibill.android.data.remote.interceptor.AuthEventBus
import com.aibill.android.presentation.navigation.AiBillNavHost
import com.aibill.android.presentation.theme.AiBillTheme
import com.aibill.android.presentation.ui.auth.AppLockScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var authEventBus: AuthEventBus

    @Inject
    lateinit var appLogger: com.aibill.android.util.AppLogger

    private val mainViewModel: MainViewModel by viewModels()

    // ★ PR 修复：isLocked 不再依赖异步 DataStore 读取，
    // 从 MainViewModel.appLockEnabled 同步消费 .value，避免冷启动+立即切后台的竞态绕过。
    private var isLocked by mutableStateOf(false)
    private var wasInBackground = false
    private var navigateTo by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        navigateTo = resolveNavigateTo(intent)
        observeAuthEvents()
        appLogger.autoCleanOldLogs(this)

        // ★ 同步初始化锁定态（解决 PR #41 覆盖不到的冷启动 race）
        isLocked = mainViewModel.appLockEnabled.value

        setContent {
            val themeMode by mainViewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicColor by mainViewModel.dynamicColorEnabled.collectAsStateWithLifecycle()
            AiBillTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                if (isLocked) {
                    AppLockScreen(onUnlocked = { isLocked = false })
                } else {
                    val startupState by mainViewModel.startupState.collectAsStateWithLifecycle()
                    if (startupState.isReady) {
                        AiBillNavHost(
                            startDestination = startupState.startRoute,
                            navigateTo = navigateTo,
                            onNavigationHandled = { navigateTo = null },
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }

        // 监听 appLockEnabled 后续变化（用户在设置中开启 / 关闭）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.appLockEnabled.collect { _ ->
                    // 监听副作用：仅作为状态信号；锁定逻辑统一在 onStart 处理。
                }
            }
        }

        // 监听 hideFromRecents 变化，实时同步到 ActivityManager
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                mainViewModel.hideFromRecents.collect { hide ->
                    applyHideFromRecents(hide)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navigateTo = resolveNavigateTo(intent)
    }

    private fun resolveNavigateTo(intent: android.content.Intent?): String? {
        if (intent == null) return null
        if (intent.action == "VIEW_TRANSACTIONS") return "transactions"
        return intent.getStringExtra("navigate_to")
    }

    override fun onStop() {
        super.onStop()
        wasInBackground = true
    }

    override fun onStart() {
        super.onStart()
        // ★ PR 修复：去掉 `appLockCheckedThisProcess` 标志，
        // 改用同步状态：只要 enabled=true 且刚切回前台，就锁定。
        // 首次 onCreate 已同步锁定，不需要额外守卫。
        if (wasInBackground && !isLocked && mainViewModel.appLockEnabled.value) {
            isLocked = true
        }
        wasInBackground = false
    }

    private fun applyHideFromRecents(hide: Boolean) {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
        activityManager?.appTasks?.firstOrNull()?.setExcludeFromRecents(hide)
    }

    private fun observeAuthEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                authEventBus.events.collect { event ->
                    when (event) {
                        is AuthEvent.TokenExpired -> {
                            navigateTo = "login_force"
                        }
                    }
                }
            }
        }
    }
}
