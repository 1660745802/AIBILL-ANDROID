package com.aibill.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aibill.android.data.local.entity.AutoRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AutoRuleDao {

    @Query("SELECT * FROM auto_rules ORDER BY created_at DESC")
    fun observeAll(): Flow<List<AutoRuleEntity>>

    @Query("SELECT * FROM auto_rules WHERE is_enabled = 1")
    suspend fun getEnabledRules(): List<AutoRuleEntity>

    @Query("SELECT * FROM auto_rules WHERE rule_type = :type AND value = :value LIMIT 1")
    suspend fun findRule(type: String, value: String): AutoRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: AutoRuleEntity)

    @Update
    suspend fun update(rule: AutoRuleEntity)

    @Query("DELETE FROM auto_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM auto_rules WHERE rule_type = :type")
    suspend fun deleteByType(type: String)

    @Query("UPDATE auto_rules SET is_enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}
