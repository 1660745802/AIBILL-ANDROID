package com.aibill.android.domain.repository

import com.aibill.android.domain.model.Category
import com.aibill.android.domain.model.Result
import kotlinx.coroutines.flow.Flow

interface CategoryRepository {
    fun observeCategories(type: String? = null): Flow<List<Category>>
    suspend fun syncCategories(): Result<Unit>
    /** PR #61：BudgetViewModel 之前直接调 categoryApi.getCategories 绕过 Repository */
    suspend fun getCategoriesOnce(): Result<List<Category>>
    /** PR #61：CategoryManageViewModel CRUD 下沉 */
    suspend fun createCategory(name: String, type: String, icon: String, sortOrder: Int): Result<Unit>
    suspend fun updateCategory(id: Int, name: String, icon: String, sortOrder: Int): Result<Unit>
    suspend fun deleteCategory(id: Int): Result<Unit>
}