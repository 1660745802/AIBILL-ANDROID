package com.aibill.android.data.local.datastore

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同步锁：标记 SyncWorker 是否正在执行。
 *
 * PR C1：解决「登录时 SyncWorker 在飞，跨账号写库」竞态。
 * - SyncWorker.doWork() 入口置 true，出口置 false（finally）
 * - AuthRepositoryImpl.login/register 先 cancelUniqueWork 再 runBlocking 等待锁释放
 * - 即使 WorkManager cancel 异步生效，最坏情况下只丢一次循环，已有 transaction 不跨用户
 *
 * 使用 EncryptedSharedPreferences 之外的简单 SharedPreferences（不需加密，
 * 仅作进程内互斥标志，非敏感）。
 */
@Singleton
class SyncLock @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 是否正在同步（true 时禁止登录/换号进入清缓存路径） */
    fun isActive(): Boolean = prefs.getBoolean(KEY_ACTIVE, false)

    /**
     * 原子置 true 并返回设置前的值。
     * 调用方负责在 finally 中调 [release]。
     */
    fun acquire(): Boolean {
        val previous = prefs.getBoolean(KEY_ACTIVE, false)
        prefs.edit { putBoolean(KEY_ACTIVE, true) }
        return previous
    }

    fun release() {
        prefs.edit { putBoolean(KEY_ACTIVE, false) }
    }

    companion object {
        private const val PREFS_NAME = "sync_lock"
        private const val KEY_ACTIVE = "sync_in_progress"
    }
}