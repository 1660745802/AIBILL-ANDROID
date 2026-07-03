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
        // 1. 先尝试本地规则匹配（减少 AI 调用）
        // 如果是简单输入且本地规则能完全解析，可直接返回
        // 本地规则目前只辅助分类匹配，完整解析仍需 AI

        // 2. 调用 AI 解析 API
        val result = safeApiCall {
            aiApi.parse(mapOf("input" to input))
        }

        return result.map { response ->
            response.items.map { dto ->
                AiParseResult(
                    type = TransactionType.fromValue(dto.type),
                    amount = dto.amount,
                    categoryId = dto.categoryId,
                    categoryName = dto.categoryName,
                    categoryIcon = dto.categoryIcon,
                    description = dto.description,
                    date = dto.date,
                    accountId = dto.accountId,
                    accountName = dto.accountName,
                    targetAccountId = dto.targetAccountId,
                    targetAccountName = dto.targetAccountName,
                )
            }
        }
    }
}
