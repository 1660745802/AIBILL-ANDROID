package com.aibill.android.domain.repository

import com.aibill.android.domain.model.Account
import com.aibill.android.domain.model.Category
import com.aibill.android.domain.model.Result
import kotlinx.coroutines.flow.Flow

interface CategoryRepository {
    fun observeCategories(type: String? = null): Flow<List<Category>>
    suspend fun syncCategories(): Result<Unit>
    /** PR #61：BudgetViewModel 之前直接调 categoryApi.getCategories 绕过 Repository */
    suspend fun getCategoriesOnce(): Result<List<Category>>
}

interface AccountRepository {
    fun observeAccounts(): Flow<List<Account>>
    suspend fun syncAccounts(): Result<Unit>
}
