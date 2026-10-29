package com.aibill.android.data.ownership

import com.aibill.android.data.remote.interceptor.TokenManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 会话级协程边界：换号/换服务器/登出时取消并等待旧任务，登录后恢复。
 *
 * 任务以当前会话 Job 为父协程；嵌套调用从协程上下文继承同一票据，
 * 不会在切换后重新取快照而写进新账号。未登录时默认暂停，避免登出后继续采集通知。
 */
@Singleton
class CaptureSession private constructor(initiallyActive: Boolean) {

    @Inject
    constructor(tokenManager: TokenManager) : this(tokenManager.hasToken())

    /** 测试入口：默认视为已登录。 */
    constructor() : this(true)

    private class SessionElement(val owner: Job) : AbstractCoroutineContextElement(SessionElement) {
        companion object Key : CoroutineContext.Key<SessionElement>
    }

    @Volatile
    private var owner: Job = newOwner(initiallyActive)

    private fun newOwner(active: Boolean): Job = SupervisorJob().also { if (!active) it.cancel() }

    fun snapshot(): Job = owner

    suspend fun <T> run(expected: Job? = null, operation: suspend () -> T): T {
        val session = expected
            ?: currentCoroutineContext()[SessionElement]?.owner
            ?: snapshot()
        session.ensureActive()
        val pending = CoroutineScope(
            currentCoroutineContext().minusKey(Job) + session + SessionElement(session),
        ).async { operation() }
        return try {
            pending.await()
        } finally {
            pending.cancel()
        }
    }

    // 清库/登出必须等旧任务的取消清理结束，不能只取消而不等待。
    suspend fun pause() {
        snapshot().cancelAndJoin()
    }

    @Synchronized
    fun resume() {
        if (!owner.isActive) owner = newOwner(true)
    }
}
