package com.aibill.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.aibill.android.data.local.entity.CaptureSampleEntity

/**
 * 被拦内容本地留存的读写口。
 *
 * 与 [AppLogDao] 的收敛策略同形（时间窗 + 条数上限），但**刻意不共用日志表**：
 * 日志表 2 天就清、且 5000 条上限对 a11y 密度远远不够——这正是 2026-10 查漏账
 * 失败的原因（见 [CaptureSampleEntity] 类头）。
 */
@Dao
interface CaptureSampleDao {

    @Insert
    suspend fun insert(sample: CaptureSampleEntity): Long

    /**
     * 丢弃保留期之前的事件（按 [CaptureSampleEntity.receivedAt]，事件时刻而非入库时刻）。
     */
    @Query("DELETE FROM capture_samples WHERE received_at < :before")
    suspend fun cleanBefore(before: Long)

    /**
     * 只留最新 [keep] 条。条数上限是**兜底**：正常路径下 30 天 ≤ 6000 条（每用户每天 200 上限）
     * 根本触不到，它只防「某天限流被绕过」这类异常把表写爆。
     */
    @Query(
        "DELETE FROM capture_samples WHERE id NOT IN " +
            "(SELECT id FROM capture_samples ORDER BY id DESC LIMIT :keep)"
    )
    suspend fun trimToKeep(keep: Int)

    /**
     * 按事件时刻倒序取最近 [limit] 条。
     *
     * 必须带 `id` 作为次序键：同一毫秒写入的多条在 SQLite 排序中不稳定
     * （`AppLogDao.getRecent` 踩过同一个坑）。
     */
    @Query("SELECT * FROM capture_samples ORDER BY received_at DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<CaptureSampleEntity>

    /** 当前留存条数。 */
    @Query("SELECT COUNT(*) FROM capture_samples")
    suspend fun count(): Int
}
