package com.aibill.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aibill.android.data.local.entity.CategoryRuleEntity

@Dao
interface CategoryRuleDao {

    @Query("SELECT * FROM category_rules WHERE keyword = :keyword LIMIT 1")
    suspend fun findByKeyword(keyword: String): CategoryRuleEntity?

    @Query("SELECT * FROM category_rules ORDER BY hit_count DESC")
    suspend fun getAll(): List<CategoryRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(rule: CategoryRuleEntity)

    @Query(
        "UPDATE category_rules SET hit_count = hit_count + 1, updated_at = :now WHERE keyword = :keyword"
    )
    suspend fun incrementHitCount(keyword: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM category_rules WHERE keyword = :keyword")
    suspend fun deleteByKeyword(keyword: String)

    @Query("DELETE FROM category_rules")
    suspend fun deleteAll()
}
