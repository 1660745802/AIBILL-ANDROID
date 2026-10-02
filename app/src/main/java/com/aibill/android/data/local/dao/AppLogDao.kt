package com.aibill.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.aibill.android.data.local.entity.AppLogEntity

@Dao
interface AppLogDao {

    @Insert
    suspend fun insert(log: AppLogEntity)

    /**
     * 按时间倒序取日志。
     * 必须带 id 作为次序键：同一毫秒写入的多条日志在 SQLite 排序中次序
     * 不稳定，导出时出现过「同步成功」排在「同步开始」之前。
     */
    @Query("SELECT * FROM app_logs ORDER BY timestamp DESC, id DESC")
    suspend fun getRecent(): List<AppLogEntity>

    /** 清理 7 天前的日志 */
    @Query("DELETE FROM app_logs WHERE timestamp < :before")
    suspend fun cleanBefore(before: Long)

    /**
     * 只保留最新 keep 条，超出的从最旧开始删。
     * 日志表原无条数上限，仅靠「打开 App 时清 2 天前」收敛，
     * 而 A11Y_PAGE 这类事件在高频页面上可达每分钟数十条，
     * 两天内就能把「真正的账务结果行」挤出导出窗口。
     */
    @Query(
        "DELETE FROM app_logs WHERE id NOT IN " +
            "(SELECT id FROM app_logs ORDER BY id DESC LIMIT :keep)"
    )
    suspend fun trimToKeep(keep: Int)

    /** 当前日志条数 */
    @Query("SELECT COUNT(*) FROM app_logs")
    suspend fun count(): Int
}
