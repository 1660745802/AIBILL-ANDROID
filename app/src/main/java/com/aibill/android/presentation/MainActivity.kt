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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.presentation.navigation.AiBillNavHost
import com.aibill.android.presentation.theme.AiBillTheme
import com.aibill.android.presentation.ui.auth.AppLockScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var userPreferences: UserPreferences

    private val mainViewModel: MainViewModel by viewModels()

    private var isLocked by mutableStateOf(false)
    private var wasInBackground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AiBillTheme {
                if (isLocked) {
                    AppLockScreen(onUnlocked = { isLocked = false })
                } else {
                    val startupState by mainViewModel.startupState.collectAsState()
                    if (startupState.isReady) {
                        AiBillNavHost(startDestination = startupState.startRoute)
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

    override fun onStop() {
        super.onStop()
        wasInBackground = true
    }

    override fun onStart() {
        super.onStart()
        if (wasInBackground) {
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
}
