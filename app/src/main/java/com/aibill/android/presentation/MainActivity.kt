package com.aibill.android.presentation

import android.app.ActivityManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.fragment.app.FragmentActivity
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.remote.interceptor.AuthEvent
import com.aibill.android.data.remote.interceptor.AuthEventBus
import com.aibill.android.presentation.navigation.AiBillNavHost
import com.aibill.android.presentation.theme.AiBillTheme
import com.aibill.android.presentation.ui.auth.AppLockScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var userPreferences: UserPreferences

    @Inject
    lateinit var authEventBus: AuthEventBus

    @Inject
    lateinit var appLogger: com.aibill.android.util.AppLogger

    private val mainViewModel: MainViewModel by viewModels()

    private var isLocked by mutableStateOf(false)
    private var wasInBackground = false
    private var navigateTo by mutableStateOf<String?>(null)
    private var aiInputPrefill by mutableStateOf<String?>(null)
    /**
     * PR #41：AppLock 启用状态是否已经在进程生命周期内确认过。
     * 冷启动（savedInstanceState 不为 null）时直接进入锁定流程，
     * 不依赖 wasInBackground 标志，避免进程被杀后重启绕过。
     */
    private var appLockCheckedThisProcess = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        navigateTo = intent?.getStringExtra("navigate_to")
        aiInputPrefill = intent?.getStringExtra("ai_input")
        observeAuthEvents()
        appLogger.autoCleanOldLogs(this) // 每次打开 App 清理 7 天前日志+文件

        // PR #41：进程启动时若启用 AppLock，立刻进入锁定态，
        // 不依赖 wasInBackground（仅靠 onStart 设置）
        lifecycleScope.launch {
            val lockEnabled = userPreferences.appLockEnabled.first()
            if (lockEnabled) {
                isLocked = true
            }
            appLockCheckedThisProcess = true
        }
        setContent {
            val themeMode by userPreferences.themeMode.collectAsStateWithLifecycle(initialValue = "system")
            AiBillTheme(themeMode = themeMode) {
                if (isLocked) {
                    AppLockScreen(onUnlocked = { isLocked = false })
                } else {
                    val startupState by mainViewModel.startupState.collectAsStateWithLifecycle()
                    if (startupState.isReady) {
                        AiBillNavHost(
                            startDestination = startupState.startRoute,
                            navigateTo = navigateTo,
                            aiInputPrefill = aiInputPrefill,
                            onNavigationHandled = { navigateTo = null },
                            onAiInputConsumed = { aiInputPrefill = null },
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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                applyHideFromRecents()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // App 已在运行时点击通知，更新导航目标
        setIntent(intent)
        navigateTo = intent.getStringExtra("navigate_to")
        intent.getStringExtra("ai_input")?.let { aiInputPrefill = it }
    }

    override fun onStop() {
        super.onStop()
        wasInBackground = true
    }

    override fun onStart() {
        super.onStart()
        // 如果本进程已经检查过 AppLock（onCreate 已经处理过 cold start 场景），
        // 这里只需要处理从后台返回的情况
        if (wasInBackground && appLockCheckedThisProcess) {
            wasInBackground = false
            lifecycleScope.launch {
                val lockEnabled = userPreferences.appLockEnabled.first()
                if (lockEnabled) {
                    isLocked = true
                }
            }
        }
    }

    private suspend fun applyHideFromRecents() {
        val hideFromRecents = userPreferences.hideFromRecents.first()
        val activityManager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
        activityManager?.appTasks?.firstOrNull()?.setExcludeFromRecents(hideFromRecents)
    }

    /**
     * 订阅 AuthEventBus：401 时 AuthInterceptor 会清 Token 并发出 TokenExpired。
     * 这里统一跳转到登录页，并弹出 Dialog 提示用户。
     */
    private fun observeAuthEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                authEventBus.events.collect { event ->
                    when (event) {
                        is AuthEvent.TokenExpired -> {
                            // 复用 navigateTo 触发 NavHost 跳 Login，由 NavHost 清栈
                            navigateTo = "login_force"
                        }
                    }
                }
            }
        }
    }
}
