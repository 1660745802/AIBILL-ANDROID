package com.aibill.android.data.repository

import com.aibill.android.data.local.dao.AccountDao
import com.aibill.android.data.local.entity.AccountEntity
import com.aibill.android.data.remote.api.CategoryApi
import com.aibill.android.data.remote.safeApiCall
import com.aibill.android.domain.model.Account
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.repository.AccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepositoryImpl @Inject constructor(
    private val accountDao: AccountDao,
    private val categoryApi: CategoryApi,
) : AccountRepository {

    override fun observeAccounts(): Flow<List<Account>> {
        return accountDao.observeAll().map { list ->
            list.map { entity ->
                Account(
                    id = entity.id,
                    name = entity.name,
                    type = entity.type,
                    icon = entity.icon,
                    currentBalance = entity.currentBalance,
                )
            }
        }
    }

    override suspend fun syncAccounts(): Result<Unit> {
        val result = safeApiCall { categoryApi.getAccounts() }
        return when (result) {
            is Result.Success -> {
                val entities = result.data.items.map { dto ->
                    AccountEntity(
                        id = dto.id,
                        name = dto.name,
                        type = dto.type,
                        icon = dto.icon,
                        currentBalance = dto.currentBalance,
                    )
                }
                accountDao.deleteAll()
                accountDao.insertAll(entities)
                Timber.d("账户同步完成, count=${entities.size}")
                Result.Success(Unit)
            }
            is Result.Error -> {
                Timber.e("账户同步失败: ${result.message}")
                result
            }
            is Result.Loading -> result
        }
    }

    /** PR #61：账户 CRUD 全部下沉 */
    override suspend fun createAccount(
        name: String, type: String, icon: String,
        initialBalance: Int, sortOrder: Int,
    ): Result<Unit> {
        val response = safeApiCall {
            categoryApi.createAccount(mapOf(
                "name" to name,
                "type" to type,
                "icon" to icon,
                "initial_balance" to initialBalance,
                "sort_order" to sortOrder,
            ))
        }
        return when (response) {
            is Result.Success -> {
                syncAccounts()
                Result.Success(Unit)
            }
            is Result.Error -> response
            is Result.Loading -> response
        }
    }

    override suspend fun updateAccount(
        id: Int, name: String, icon: String, initialBalance: Int,
    ): Result<Unit> {
        val response = safeApiCall {
            categoryApi.updateAccount(id, mapOf(
                "name" to name,
                "icon" to icon,
                "initial_balance" to initialBalance,
            ))
        }
        return when (response) {
            is Result.Success -> {
                syncAccounts()
                Result.Success(Unit)
            }
            is Result.Error -> response
            is Result.Loading -> response
        }
    }

    override suspend fun deleteAccount(id: Int): Result<Unit> {
        return safeApiCall { categoryApi.deleteAccount(id) }.let { response ->
            when (response) {
                is Result.Success -> {
                    syncAccounts()
                    Result.Success(Unit)
                }
                is Result.Error -> response
                is Result.Loading -> response
            }
        }
    }
}
