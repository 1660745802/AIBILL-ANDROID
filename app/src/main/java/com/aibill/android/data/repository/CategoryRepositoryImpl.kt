package com.aibill.android.data.repository

import com.aibill.android.data.local.dao.CategoryDao
import com.aibill.android.data.local.entity.CategoryEntity
import com.aibill.android.data.remote.api.CategoryApi
import com.aibill.android.data.remote.safeApiCall
import com.aibill.android.domain.model.Category
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.domain.repository.CategoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepositoryImpl @Inject constructor(
    private val categoryDao: CategoryDao,
    private val categoryApi: CategoryApi,
) : CategoryRepository {

    override fun observeCategories(type: String?): Flow<List<Category>> {
        val flow = if (type != null) {
            categoryDao.observeByType(type)
        } else {
            categoryDao.observeAll()
        }
        return flow.map { list ->
            list.map { entity ->
                Category(
                    id = entity.id,
                    name = entity.name,
                    type = TransactionType.fromValue(entity.type) ?: TransactionType.EXPENSE,
                    icon = entity.icon,
                    sortOrder = entity.sortOrder,
                )
            }
        }
    }

    override suspend fun syncCategories(): Result<Unit> {
        val result = safeApiCall { categoryApi.getCategories() }
        return when (result) {
            is Result.Success -> {
                val entities = result.data.items.map { dto ->
                    CategoryEntity(
                        id = dto.id,
                        name = dto.name,
                        type = dto.type,
                        icon = dto.icon,
                        sortOrder = dto.sortOrder,
                    )
                }
                categoryDao.deleteAll()
                categoryDao.insertAll(entities)
                Timber.d("分类同步完成, count=${entities.size}")
                Result.Success(Unit)
            }
            is Result.Error -> {
                Timber.e("分类同步失败: ${result.message}")
                result
            }
            is Result.Loading -> result
        }
    }

    /**
     * PR #61：一次性拉取分类列表（不写库），供 ViewModel 之前直接调 API 的场景下沉
     */
    override suspend fun getCategoriesOnce(): Result<List<Category>> {
        return safeApiCall { categoryApi.getCategories() }.map { response ->
            response.items.map { dto ->
                Category(
                    id = dto.id,
                    name = dto.name,
                    type = TransactionType.fromValue(dto.type) ?: TransactionType.EXPENSE,
                    icon = dto.icon,
                    sortOrder = dto.sortOrder,
                )
            }
        }
    }

    /** PR #61：分类 CRUD 下沉 */
    override suspend fun createCategory(name: String, type: String, icon: String, sortOrder: Int): Result<Unit> {
        val response = safeApiCall {
            categoryApi.createCategory(mapOf(
                "name" to name, "type" to type, "icon" to icon, "sort_order" to sortOrder,
            ))
        }
        return when (response) {
            is Result.Success -> { syncCategories(); Result.Success(Unit) }
            is Result.Error -> response
            is Result.Loading -> response
        }
    }

    override suspend fun updateCategory(id: Int, name: String, icon: String, sortOrder: Int): Result<Unit> {
        val response = safeApiCall {
            categoryApi.updateCategory(id, mapOf(
                "name" to name, "icon" to icon, "sort_order" to sortOrder,
            ))
        }
        return when (response) {
            is Result.Success -> { syncCategories(); Result.Success(Unit) }
            is Result.Error -> response
            is Result.Loading -> response
        }
    }

    override suspend fun deleteCategory(id: Int): Result<Unit> {
        return when (val response = safeApiCall { categoryApi.deleteCategory(id) }) {
            is Result.Success -> { syncCategories(); Result.Success(Unit) }
            is Result.Error -> response
            is Result.Loading -> response
        }
    }
}
