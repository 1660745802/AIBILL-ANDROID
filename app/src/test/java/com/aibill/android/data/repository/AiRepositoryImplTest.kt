package com.aibill.android.data.repository

import com.aibill.android.data.remote.api.AiApi
import com.aibill.android.data.remote.dto.request.AiParseRequest
import com.aibill.android.data.remote.dto.response.AiParseResponseDto
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.domain.usecase.CategoryLearningEngine
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 手工 AI 记账路径的请求契约。
 *
 * 服务端已全局保证 `POST /api/ai/parse` 的 `items` ≤ 1，`max_items` 死字段已删；
 * 这里钉住手工路径仍然只发 `input` + `channel=manual`，不会凭空多出参数。
 */
class AiRepositoryImplTest {

    private val aiApi: AiApi = mockk()
    private val categoryLearningEngine: CategoryLearningEngine = mockk(relaxed = true)
    private val repository = AiRepositoryImpl(aiApi, categoryLearningEngine)

    @Test
    @DisplayName("手工 AI 记账路径请求不含 max_items（服务端已全局单笔）")
    fun `手工路径不含 max_items`() = runTest {
        val sent = slot<AiParseRequest>()
        coEvery { aiApi.parse(capture(sent)) } returns ApiResponse(
            code = 0,
            data = AiParseResponseDto(items = emptyList(), rawInput = "午餐 32"),
            message = "success",
        )

        repository.parseInput("午餐 32")

        assertFalse(
            sent.captured::class.java.declaredFields.any { it.name == "maxItems" },
            "max_items 已随服务端全局单笔保证删除，请求不应再有该字段",
        )
        assertEquals("manual", sent.captured.channel)
    }
}
