package com.aibill.android.presentation

import android.app.ActivityManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aibill.android.data.remote.interceptor.AuthEvent
import com.aibill.android.data.remote.interceptor.AuthEventBus
import com.aibill.android.data.remote.interceptor.SessionEndReason
import com.aibill.android.data.remote.interceptor.TokenManager
import com.aibill.android.presentation.navigation.AiBillNavHost
import com.aibill.android.presentation.theme.AiBillTheme
import com.aibill.android.presentation.ui.auth.AppLockScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import android.widget.Toast
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var authEventBus: AuthEventBus

    @Inject
    lateinit var tokenManager: TokenManager

    @Inject
    lateinit var appLogger: com.aibill.android.util.AppLogger

    private val mainViewModel: MainViewModel by viewModels()

    private var navigateTo by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        navigateTo = resolveNavigateTo(intent)
        observeAuthEvents()
        notifyIfSessionExpiredInBackground()
        appLogger.autoCleanOldLogs(this)

        setContent {
            val themeMode by mainViewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicColor by mainViewModel.dynamicColorEnabled.collectAsStateWithLifecycle()
            val isLocked by mainViewModel.isLocked.collectAsStateWithLifecycle()
            AiBillTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                if (isLocked == null) {
                    com.aibill.android.presentation.components.LoadingState()
                } else if (isLocked == true) {
                    AppLockScreen(onUnlocked = mainViewModel::onUnlocked)
                } else {
                    val startupState by mainViewModel.startupState.collectAsStateWithLifecycle()
                    if (startupState.isReady) {
                        AiBillNavHost(
                            startDestination = startupState.startRoute,
                            navigateTo = navigateTo,
                            onNavigationHandled = { navigateTo = null },
                        )
                    } else {
                        // 走共享 LoadingState，保证启动加载态的指示器尺寸/颜色
                        // 和应用内其它加载态一致（原来是裸 CircularProgressIndicator()）。
                        com.aibill.android.presentation.components.LoadingState()
                    }
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
        mainViewModel.onHostStopped(isChangingConfigurations)
    }

    override fun onStart() {
        super.onStart()
        // 配置变更不重锁；真实后台返回由 ViewModel 的后台标记识别。
        mainViewModel.onHostStarted()
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

    /**
     * 补登过期提示（D1）。
     *
     * 背景：`AuthEventBus` 是无 replay 的 SharedFlow，而这里只在 `STARTED`
     * 期间 collect。当 401 发生在**后台**（典型场景：通知监听服务调 AI 时
     * token 过期）时，MainActivity 不在前台 → 事件被丢弃，但
     * `AuthInterceptor` 已经结束会话。用户下次打开 App 只能看到登录页，
     * 完全不知道「什么时候、被什么踢的」。
     *
     * 这里在冷启动时主动读一次**持久化的会话结束原因**
     * （[TokenManager.getSessionEndReason]，由 `endSession` 写入、`saveSession` 清除）：
     *
     * - [SessionEndReason.TOKEN_EXPIRED] → 真的被过期踢掉，给出说明
     * - [SessionEndReason.SIGNED_OUT] → 用户自己退出的，属于预期内，**不打扰**
     * - `null` → 无已知的会话结束（首次安装 / 已重新登录），不提示
     *
     * ⚠️ 不可回退到 `lastKnownUserId != null && !hasToken()` 这种猜测：
     * 登出保留 `last_known_user_id` 之后（B6 修复），主动登出也会命中那个条件，
     * 从而把「我自己退出的」误报成「登录已过期」。
     */
    private fun notifyIfSessionExpiredInBackground() {
        if (tokenManager.hasToken()) return
        when (tokenManager.getSessionEndReason()) {
            SessionEndReason.TOKEN_EXPIRED -> {
                appLogger.info("AUTH", "检测到会话被后台 401 结束，展示过期提示")
                Toast.makeText(this, "登录已过期，请重新登录", Toast.LENGTH_LONG).show()
            }
            SessionEndReason.SIGNED_OUT -> {
                // 用户主动退出或未带凭证被拒：不提示
            }
            null -> {
                // 首次安装或已重新登录：没有会话结束记录
            }
        }
    }
}
