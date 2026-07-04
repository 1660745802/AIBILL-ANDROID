package com.aibill.android.data.repository

import com.aibill.android.data.remote.api.AiApi
import com.aibill.android.data.remote.safeApiCall
import com.aibill.android.domain.model.AiParseResult
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.domain.repository.AiRepository
import com.aibill.android.domain.usecase.CategoryLearningEngine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiRepositoryImpl @Inject constructor(
    private val aiApi: AiApi,
    private val categoryLearningEngine: CategoryLearningEngine,
) : AiRepository {

    override suspend fun parseInput(input: String): Result<List<AiParseResult>> {
        // 1. 调用 AI 解析 API（金额/日期/描述/账户等仍由 AI 处理）
        val result = safeApiCall {
            aiApi.parse(mapOf("input" to input))
        }

        return when (result) {
            is Result.Success -> {
                val response = result.data
                val items = response.items.map { dto ->
                    val aiCategoryId = dto.categoryId
                    val description = dto.description

                    // 2. 命中本地学习规则 → 用本地分类覆盖 AI 分类
                    // （PRD §8.5 分层解析：本地规则优先，减少 AI 调用 + 更准确）
                    val localCategoryId = if (!description.isNullOrBlank()) {
                        categoryLearningEngine.matchCategory(description)
                    } else null
                    val finalCategoryId = localCategoryId ?: aiCategoryId

                    AiParseResult(
                        type = TransactionType.fromValue(dto.type),
                        amount = dto.amount,
                        categoryId = finalCategoryId,
                        categoryName = dto.categoryName,
                        categoryIcon = dto.categoryIcon,
                        description = description,
                        date = dto.date,
                        accountId = dto.accountId,
                        accountName = dto.accountName,
                        targetAccountId = dto.targetAccountId,
                        targetAccountName = dto.targetAccountName,
                    )
                }
                Result.Success(items)
            }
            is Result.Error -> result
            is Result.Loading -> result
        }
    }
}
