package com.aibill.android.domain.repository

import com.aibill.android.domain.model.Category
import com.aibill.android.domain.model.Result
import kotlinx.coroutines.flow.Flow

interface CategoryRepository {
    fun observeCategories(type: String? = null): Flow<List<Category>>
    suspend fun syncCategories(): Result<Unit>
    /** PR #61：存在过 UI 层直接调 categoryApi 绕过 Repository 的情况，统一收口到这里 */
    suspend fun getCategoriesOnce(): Result<List<Category>>
    /** PR #61：CategoryManageViewModel CRUD 下沉 */
    suspend fun createCategory(name: String, type: String, icon: String, sortOrder: Int): Result<Unit>
    suspend fun updateCategory(id: Int, name: String, icon: String, sortOrder: Int): Result<Unit>
    suspend fun deleteCategory(id: Int): Result<Unit>

    /** 删除所有本地缓存分类（切换服务器时调用） */
    suspend fun deleteAll()
}
