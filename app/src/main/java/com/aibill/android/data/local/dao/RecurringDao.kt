package com.aibill.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aibill.android.data.local.entity.RecurringRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecurringDao {

    @Query("SELECT * FROM recurring_rules WHERE is_enabled = 1 ORDER BY day_of_month ASC")
    fun observeEnabledRules(): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rules ORDER BY created_at DESC")
    fun observeAllRules(): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rules WHERE is_enabled = 1 AND day_of_month = :day")
    suspend fun getRulesForDay(day: Int): List<RecurringRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: RecurringRuleEntity): Long

    @Update
    suspend fun update(rule: RecurringRuleEntity)

    @Query("UPDATE recurring_rules SET is_enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE recurring_rules SET last_executed_at = :timestamp WHERE id = :id")
    suspend fun updateLastExecuted(id: Long, timestamp: Long)

    @Query("DELETE FROM recurring_rules WHERE id = :id")
    suspend fun deleteById(id: Long)
}
