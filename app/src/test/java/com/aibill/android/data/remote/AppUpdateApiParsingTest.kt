package com.aibill.android.data.remote

import com.aibill.android.data.remote.api.AppUpdateDto
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AppUpdateApi 真实 JSON 解析测试（防回归）。
 *
 * 历史 bug（v1.3.0~v1.5.0 中招）：
 * checkUpdate 直接返回 AppUpdateDto（无 ApiResponse 包装），
 * 服务端响应是 `{code, data: {has_update, ...}, message}` 三层结构，
 * Moshi 在外层找不到 has_update → 静默取默认 false → 永远误判"已是最新"。
 *
 * UpdateManagerFallbackTest 因 mock 直接构造 DTO 绕过了 Moshi 解析，未发现。
 * 本测试用**服务端真实响应 JSON** 走 Moshi codegen 解析，防回归。
 */
class AppUpdateApiParsingTest {

    private val moshi = Moshi.Builder().build()  // 使用 KSP 生成的 codegen adapter

    private val wrappedAdapter: JsonAdapter<ApiResponse<AppUpdateDto>> = moshi.adapter(
        Types.newParameterizedType(ApiResponse::class.java, AppUpdateDto::class.java),
    )

    private val flatAdapter: JsonAdapter<AppUpdateDto> = moshi.adapter(AppUpdateDto::class.java)

    /** 服务端 GET /api/app/update 的真实响应（billserver app-update.ts 返回结构） */
    private val serverResponse = """
        {
          "code": 0,
          "data": {
            "has_update": true,
            "latest_version": "1.5.0",
            "latest_version_code": 7,
            "force_update": false,
            "changelog": "v1.5.0: UI 系统重构",
            "apk_url": "http://39.107.82.167:3000/updates/aibill-1.5.0.apk",
            "apk_size": 4532329
          },
          "message": ""
        }
    """.trimIndent()

    @Test
    fun `真实服务端响应 - ApiResponse 解包后 has_update=true`() {
        val response = requireNotNull(wrappedAdapter.fromJson(serverResponse))

        assertEquals(0, response.code)
        val dto = requireNotNull(response.data) {
            "data 必须被解包 — 历史 bug 中直接按 AppUpdateDto 解析外层，data 整个丢失"
        }
        assertTrue(dto.hasUpdate, "has_update 必须为 true — 历史 bug 中此值静默取了 false")
        assertEquals("1.5.0", dto.latestVersion)
        assertEquals(7, dto.latestVersionCode)
        assertEquals("http://39.107.82.167:3000/updates/aibill-1.5.0.apk", dto.apkUrl)
    }

    @Test
    fun `has_update=false 的响应 - 解包保持 false（语义正确）`() {
        val noUpdateResponse = """
            {
              "code": 0,
              "data": {
                "has_update": false,
                "latest_version": "1.3.1",
                "latest_version_code": 5,
                "force_update": false,
                "changelog": null,
                "apk_url": null,
                "apk_size": null
              },
              "message": ""
            }
        """.trimIndent()

        val response = requireNotNull(wrappedAdapter.fromJson(noUpdateResponse))
        val dto = requireNotNull(response.data)
        assertFalse(dto.hasUpdate, "has_update=false 必须保持 false")
        assertEquals("1.3.1", dto.latestVersion)
    }

    @Test
    fun `直接按 AppUpdateDto 解析外层 - 复现历史 bug（has_update 静默 false）`() {
        // 历史 bug 最小复现：旧版 checkUpdate 直接返回 AppUpdateDto，
        // Moshi 在响应外层找不到 has_update → 取默认值 false → 永远"已是最新"
        val dto = requireNotNull(flatAdapter.fromJson(serverResponse))

        assertFalse(dto.hasUpdate, "复现历史 bug：外层解析拿不到 data 里的 has_update")
        // 结论：API 必须返回 ApiResponse 包装，queryBillserver 必须解包 data
    }
}
