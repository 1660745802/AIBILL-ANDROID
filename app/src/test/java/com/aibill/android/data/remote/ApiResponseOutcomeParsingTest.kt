package com.aibill.android.data.remote

import com.aibill.android.data.remote.dto.response.AiParseResponseDto
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 顶层 `outcome` 的解析（服务端 022 新增，仅 `/api/ai/parse` 使用）。
 *
 * `not_a_bill` / `unavailable` 决定客户端把「判非账」与「AI 挂了」分成两桶计数，
 * 所以两个取值都必须被读出来；旧服务端无该字段 → null。
 *
 * 顺带钉住 Moshi 的姿态：**不开 `failOnUnknown`**，未知键（如未来新增字段）
 * 必须被忽略而不是解析失败。
 *
 * 注：本文件原为 `AiParseLogIdParsingTest`，其中的 `parse_log_id` 解析用例随
 * parse-feedback 链路一起下线（字段仍在 DTO 上保留，仅为契约同步）。
 */
class ApiResponseOutcomeParsingTest {

    private val moshi: Moshi = Moshi.Builder().build()

    private fun adapter() = moshi.adapter<ApiResponse<AiParseResponseDto>>(
        Types.newParameterizedType(ApiResponse::class.java, AiParseResponseDto::class.java)
    )

    @Test
    @DisplayName("顶层 outcome 被读出来（022）：not_a_bill / unavailable；缺失=null（旧服务端，未知键忽略）")
    fun readsTopLevelOutcome() {
        val notABill = adapter().fromJson(
            """{"code":5001,"message":"未识别","data":null,"outcome":"not_a_bill"}"""
        )!!
        assertEquals("not_a_bill", notABill.outcome)

        val unavailable = adapter().fromJson(
            """{"code":5002,"message":"格式异常","data":null,"outcome":"unavailable"}"""
        )!!
        assertEquals("unavailable", unavailable.outcome)

        // 旧服务端无该字段 → null；未知键（future_field）必须被忽略而不是解析失败（不 failOnUnknown）
        val legacy = adapter().fromJson(
            """{"code":5001,"message":"未识别","data":null,"future_field":123}"""
        )!!
        assertNull(legacy.outcome)
    }
}
