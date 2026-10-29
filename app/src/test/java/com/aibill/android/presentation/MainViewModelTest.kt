package com.aibill.android.presentation

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.remote.interceptor.TokenManager
import com.aibill.android.presentation.navigation.Route
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 对真实 DataStore 偏好流加首读屏障，覆盖启动路由比锁偏好先就绪的竞态。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MainViewModelTest {
    private val store = ViewModelStore()
    private val readGate = CompletableDeferred<Unit>()
    private lateinit var realPreferences: UserPreferences

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        realPreferences = UserPreferences(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(): MainViewModel {
        val preferences = mockk<UserPreferences> {
            every { appLockEnabled } returns flow {
                readGate.await()
                emitAll(realPreferences.appLockEnabled)
            }
            every { serverUrl } returns flowOf("https://example.invalid/api/")
            every { themeMode } returns flowOf("system")
            every { dynamicColorEnabled } returns flowOf(false)
            every { hideFromRecents } returns flowOf(false)
        }
        val tokens = mockk<TokenManager> { every { hasToken() } returns true }
        return MainViewModel(tokens, preferences).also { store.put("main", it) }
    }

    @Test
    fun `cold start cannot open ledger before delayed enabled preference arrives`() = runTest {
        realPreferences.setAppLockEnabled(true)
        val vm = viewModel()
        vm.onAppForegrounded()
        runCurrent()
        assertNull(vm.appLockEnabled.value)
        assertNull(vm.isLocked.value)
        assertFalse(vm.startupState.value.isReady)
        // 连首读前立刻后台再回前台也不得绕过。
        vm.onAppForegrounded()
        vm.onUnlocked()
        assertNull(vm.isLocked.value)

        readGate.complete(Unit)
        assertEquals(true, vm.isLocked.first { it != null })
        assertEquals(Route.Home, vm.startupState.first { it.isReady }.startRoute)
        assertTrue(vm.isLocked.value == true)
    }

    @Test
    fun `disabled lock opens only after real preference is loaded`() = runTest {
        realPreferences.setAppLockEnabled(false)
        val vm = viewModel()
        runCurrent()
        assertNull(vm.isLocked.value)
        assertFalse(vm.startupState.value.isReady)

        readGate.complete(Unit)
        assertEquals(false, vm.isLocked.first { it != null })
        assertTrue(vm.startupState.first { it.isReady }.isReady)
        vm.onAppForegrounded()
        assertEquals(false, vm.isLocked.value)
    }

    @Test
    fun `unlock survives preference emissions and subscriptions but foreground relocks`() = runTest {
        realPreferences.setAppLockEnabled(true)
        val vm = viewModel()
        readGate.complete(Unit)
        vm.isLocked.first { it == true }
        vm.onUnlocked()
        assertEquals(false, vm.isLocked.first())
        assertEquals(false, vm.isLocked.first()) // 重订阅不能重新上锁。

        realPreferences.setAppLockEnabled(false)
        vm.appLockEnabled.first { it == false }
        realPreferences.setAppLockEnabled(true)
        vm.appLockEnabled.first { it == true }
        assertEquals(false, vm.isLocked.value)
        vm.onAppForegrounded()
        assertEquals(true, vm.isLocked.value)
        vm.onUnlocked()
        assertEquals(false, vm.isLocked.value)
    }

    @Test
    fun `storage read failure fails closed instead of hanging on loading`() = runTest {
        val preferences = mockk<UserPreferences> {
            every { appLockEnabled } returns flow { throw java.io.IOException("corrupt datastore") }
            every { serverUrl } returns flowOf("https://example.invalid/api/")
            every { themeMode } returns flowOf("system")
            every { dynamicColorEnabled } returns flowOf(false)
            every { hideFromRecents } returns flowOf(false)
        }
        val tokens = mockk<TokenManager> { every { hasToken() } returns true }
        val vm = MainViewModel(tokens, preferences)

        assertEquals(true, vm.isLocked.first { it != null })
        assertEquals(Route.Home, vm.startupState.first { it.isReady }.startRoute)
    }

    @Test
    fun `background relocks across configuration recreation but rotation does not`() = runTest {
        realPreferences.setAppLockEnabled(true)
        val vm = viewModel()
        readGate.complete(Unit)
        vm.isLocked.first { it == true }

        vm.onUnlocked()
        vm.onHostStopped(changingConfigurations = true)
        vm.onHostStarted()
        assertEquals("配置变更不得重新上锁", false, vm.isLocked.value)

        vm.onHostStopped(changingConfigurations = false)
        vm.onHostStarted()
        assertEquals("真实后台返回必须重新上锁", true, vm.isLocked.value)
    }
}
