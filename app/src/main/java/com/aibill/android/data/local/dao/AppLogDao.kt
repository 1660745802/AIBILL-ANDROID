package com.aibill.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.aibill.android.data.local.entity.AppLogEntity

@Dao
interface AppLogDao {

    @Insert
    suspend fun insert(log: AppLogEntity)

    @Query("SELECT * FROM app_logs ORDER BY timestamp DESC")
    suspend fun getRecent(): List<AppLogEntity>

    /** 清理 7 天前的日志 */
    @Query("DELETE FROM app_logs WHERE timestamp < :before")
    suspend fun cleanBefore(before: Long)
}
