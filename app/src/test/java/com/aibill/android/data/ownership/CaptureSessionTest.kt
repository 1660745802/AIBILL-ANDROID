package com.aibill.android.data.ownership

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import io.mockk.every
import io.mockk.mockk
import com.aibill.android.data.remote.interceptor.TokenManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureSessionTest {
    @Test
    fun `switch waits for old writes to stop before allowing new work`() = runTest {
        val session = CaptureSession()
        val writes = mutableListOf<String>()
        val finishing = CompletableDeferred<Unit>()
        val old = launch {
            session.run {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        finishing.await()
                        writes += "old cleanup"
                    }
                }
            }
        }
        runCurrent()
        val pause = async { session.pause() }
        runCurrent()
        assertFalse(pause.isCompleted)
        finishing.complete(Unit)
        pause.await()
        old.join()
        writes.clear()
        session.resume()
        session.run { writes += "new" }
        assertEquals(listOf("new"), writes)
    }

    @Test
    fun `old scoring callback cannot run after resume`() = runTest {
        val session = CaptureSession()
        val oldOwner = session.snapshot()
        session.pause()
        session.resume()
        var wrote = false
        val stale = launch { session.run(oldOwner) { wrote = true } }
        stale.join()
        assertTrue(stale.isCancelled)
        assertFalse(wrote)
        assertEquals("current", session.run { "current" })
    }

    @Test
    fun `caller cancellation also cancels its owned operation`() = runTest {
        val session = CaptureSession()
        var stopped = false
        val caller = launch {
            session.run {
                try {
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
        }
        runCurrent()
        caller.cancel()
        runCurrent()
        assertTrue(stopped)
        assertTrue(session.snapshot().isActive)
    }

    @Test
    fun `cold start without token stays paused until resume`() = runTest {
        val session = CaptureSession(mockk<TokenManager> { every { hasToken() } returns false })
        var ran = false
        val attempt = launch { session.run { ran = true } }
        attempt.join()
        assertTrue(attempt.isCancelled)
        assertFalse(ran)
        session.resume()
        assertEquals("ok", session.run { "ok" })
    }

    @Test
    fun `paused captures never execute`() = runTest {
        val session = CaptureSession()
        session.pause()
        var ran = false
        val attempt = launch { session.run { ran = true } }
        attempt.join()
        assertTrue(attempt.isCancelled)
        assertFalse(ran)
    }

    @Test
    fun `nested work cannot escape a paused session`() = runTest {
        val session = CaptureSession()
        val gate = CompletableDeferred<Unit>()
        var nestedRan = false
        val outer = launch {
            session.run {
                withContext(NonCancellable) {
                    gate.await()
                    runCatching { session.run { nestedRan = true } }
                }
            }
        }
        runCurrent()
        val pause = async { session.pause() }
        runCurrent()
        // 新会话已就绪，但旧外层仍绑定旧票据：内层不得借新会话写入。
        session.resume()
        gate.complete(Unit)
        pause.await()
        outer.join()
        assertTrue(outer.isCancelled)
        assertFalse(nestedRan)
    }
}
