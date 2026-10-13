package com.aibill.android.data.notification

import com.aibill.android.di.ApplicationScope
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.CaptureIngress
import com.aibill.android.domain.notification.NotificationPrefilter
import com.aibill.android.domain.notification.NotificationSink
import com.aibill.android.domain.notification.Verdict
import com.aibill.android.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CaptureIngress] 的实现：三个平台 adapter 的共同尾巴。
 *
 * 在注入的 `@ApplicationScope` 上跑 `prefilter.evaluate → Verdict.Pass → sink.process`，
 * **唯一一处**处理取消/异常：
 * - `CancellationException` 重抛（不吞，破坏结构化取消会让「进程回收/服务重建」被当成处理失败）；
 * - 其余异常只记日志不外抛（进程级 Scope 无 `CoroutineExceptionHandler`，逃出去=崩进程）。
 *
 * 这正是 C02 要修的：此前三个 adapter 各写一份，且 SMS 那份把 `CancellationException`
 * 当普通异常吞掉了。
 */
@Singleton
class CaptureIngressImpl @Inject constructor(
    private val prefilter: NotificationPrefilter,
    private val sink: NotificationSink,
    private val appLogger: AppLogger,
    @ApplicationScope private val scope: CoroutineScope,
) : CaptureIngress {

    override fun admit(capture: Capture): Job = scope.launch {
        val tag = capture.channel.logTag
        try {
            when (val verdict = prefilter.evaluate(capture)) {
                is Verdict.Block ->
                    appLogger.debug(tag, "预筛拦截(${verdict.reason}): pkg=${capture.packageName}")
                is Verdict.Pass -> sink.process(verdict.capture)
            }
        } catch (e: CancellationException) {
            // 取消不是处理失败：吞掉会让调用方在已取消的协程里继续跑。
            throw e
        } catch (e: Exception) {
            appLogger.error(tag, "处理异常: pkg=${capture.packageName} err=${e.message}")
        }
    }
}
