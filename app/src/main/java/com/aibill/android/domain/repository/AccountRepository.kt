package com.aibill.android.domain.repository

import com.aibill.android.domain.model.Account
import com.aibill.android.domain.model.Result
import kotlinx.coroutines.flow.Flow

interface AccountRepository {
    fun observeAccounts(): Flow<List<Account>>
    suspend fun syncAccounts(): Result<Unit>
    /** PR #61：账户 CRUD（之前 AccountManageViewModel 直接调 categoryApi.createAccount/updateAccount/deleteAccount） */
    suspend fun createAccount(
        name: String, type: String, icon: String,
        initialBalance: Int, sortOrder: Int,
    ): Result<Unit>
    suspend fun updateAccount(
        id: Int, name: String, icon: String, initialBalance: Int,
    ): Result<Unit>
    suspend fun deleteAccount(id: Int): Result<Unit>

    /** 删除所有本地缓存账户（切换服务器时调用） */
    suspend fun deleteAll()
}