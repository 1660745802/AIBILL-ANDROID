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
 * `parse_log_id` 的解析（反馈闭环的钥匙）。
 *
 * 回归背景：服务端一直有这个字段，但客户端 DTO 没声明 → Moshi 静默丢弃 →
 * 「用户纠正 → 服务端学习」的闭环断了很久，而且**不报错**（这正是未知字段被忽略的代价）。
 * 所以这里把两种位置都钉住：
 * - `data.parse_log_id`（快捷解析 / 模型成功）
 * - 顶层 `parse_log_id`（模型空结果，code=5001）
 *
 * 另外钉住「没有可反馈对象」的形态：服务端预筛挡下（`source:prefilter`）不带该字段，
 * 客户端必须拿到 null 而不是编造 id —— 否则会拿着无效 id 去反馈。
 */
class AiParseLogIdParsingTest {

    private val moshi: Moshi = Moshi.Builder().build()

    private fun adapter() = moshi.adapter<ApiResponse<AiParseResponseDto>>(
        Types.newParameterizedType(ApiResponse::class.java, AiParseResponseDto::class.java)
    )

    @Test
    @DisplayName("data.parse_log_id 被读出来（模型成功形态）")
    fun readsDataLevelParseLogId() {
        val json = """
            {"code":0,"message":"","parse_log_id":null,
             "data":{"items":[],"raw_input":"wx 20.00","parse_log_id":12345},
             "source":"ai"}
        """.trimIndent()

        val response = adapter().fromJson(json)!!

        assertEquals(12345L, response.data?.parseLogId)
        assertNull(response.parseLogId)
    }

    @Test
    @DisplayName("顶层 parse_log_id 被读出来（模型空结果，code=5001）")
    fun readsTopLevelParseLogId() {
        val json = """
            {"code":5001,"message":"未识别","data":null,"parse_log_id":67890,"fallback":"manual"}
        """.trimIndent()

        val response = adapter().fromJson(json)!!

        assertEquals(67890L, response.parseLogId)
        assertNull(response.data)
    }

    @Test
    @DisplayName("服务端预筛挡下的形态没有 parse_log_id → 客户端拿到 null（不编造 id）")
    fun prefilterShapeHasNoParseLogId() {
        val json = """
            {"code":0,"message":"",
             "data":{"items":[],"raw_input":"点击领取优惠券","source":"prefilter"}}
        """.trimIndent()

        val response = adapter().fromJson(json)!!

        assertNull(response.parseLogId)
        assertNull(response.data?.parseLogId)
    }
}
