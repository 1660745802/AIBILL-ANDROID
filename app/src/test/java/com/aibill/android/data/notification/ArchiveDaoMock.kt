package com.aibill.android.data.notification


import com.aibill.android.data.local.dao.CaptureSampleDao
import io.mockk.mockk

/**
 * 单测用的本地留存 DAO 替身。
 *
 * `CaptureSamplerImpl` 的 Room 镜像写在**独立协程 scope** 上（不进热路径、失败只 warn），
 * 所以绝大多数单测对留存没有任何断言需求——relaxed mock 吞掉所有 suspend 调用即可，
 * 既不用起真实 Room（单测环境无 Android 上下文），也不用让每个测试类各写一份 mock。
 *
 * 需要**真的断言落盘内容**的用例（见 `CaptureSampleArchiveTest`）自己 mockk `coVerify`，
 * 不用这个。
 */
internal fun archiveDao(): CaptureSampleDao = mockk(relaxed = true)
